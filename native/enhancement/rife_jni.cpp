// RIFE v4.6 flownet and Warp shader contract from nihui/rife-ncnn-vulkan
// a7532fc3f9f8f008cd6eecd6f2ffe2a9698e0cf7, MIT.
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <new>
#include <vector>
#include <command.h>
#include <gpu.h>
#include <layer.h>
#include <net.h>
#include <pipeline.h>

#include "rife_warp_shader.h"

const ncnn::VulkanDevice* mangaflow_vulkan_device();

namespace {
// RIFE's inputs are padded dynamically; retain full source pixels within the
// admitted 1080p envelope shared with AndroidLiveRifeVulkan.
constexpr int kMaximumPixels = 1920 * 1080;
constexpr int kMaximumSide = 1920;

class Warp final : public ncnn::Layer {
public:
    Warp() {
        support_vulkan = true;
        support_vulkan_packing = false;
    }

    int create_pipeline(const ncnn::Option& options) override {
        if (!vkdev) return -1;
        std::vector<uint32_t> spirv;
        if (ncnn::compile_spirv_module(kRifeWarpShader, options, spirv) != 0 || spirv.empty()) return -1;
        auto candidate = std::unique_ptr<ncnn::Pipeline>(new (std::nothrow) ncnn::Pipeline(vkdev));
        if (!candidate) return -1;
        candidate->set_optimal_local_size_xyz();
        std::vector<ncnn::vk_specialization_type> specializations;
        const int result = candidate->create(spirv.data(), spirv.size() * sizeof(uint32_t), specializations);
        if (result == 0) pipeline = std::move(candidate);
        return result;
    }

    int destroy_pipeline(const ncnn::Option&) override {
        pipeline.reset();
        return 0;
    }

    int forward(const std::vector<ncnn::VkMat>& source, std::vector<ncnn::VkMat>& results,
        ncnn::VkCompute& commands, const ncnn::Option& options) const override {
        if (!pipeline || source.size() != 2 || results.size() != 1) return -1;
        const auto& image = source[0];
        const auto& flow = source[1];
        if (image.dims != 3 || flow.dims != 3 || image.w != flow.w || image.h != flow.h ||
            image.cstep != flow.cstep || image.elempack != 1 || flow.elempack != 1 || flow.c < 2) return -1;

        auto& output = results[0];
        output.create(image.w, image.h, image.c, image.elemsize, image.elempack, options.blob_vkallocator);
        if (output.empty() || output.cstep != image.cstep) return -100;
        std::vector<ncnn::VkMat> bindings{image, flow, output};
        std::vector<ncnn::vk_constant_type> constants(4);
        constants[0].i = output.w;
        constants[1].i = output.h;
        constants[2].i = output.c;
        constants[3].i = output.cstep;
        commands.record_pipeline(pipeline.get(), bindings, constants, output);
        return 0;
    }

private:
    std::unique_ptr<ncnn::Pipeline> pipeline;
};

ncnn::Layer* create_warp(void*) {
    return new (std::nothrow) Warp;
}

struct Model {
    explicit Model(const ncnn::VulkanDevice* value) : device(value),
        blob(device->acquire_blob_allocator()), staging(device->acquire_staging_allocator()) {}
    ~Model() {
        net.clear();
        if (blob) device->reclaim_blob_allocator(blob);
        if (staging) device->reclaim_staging_allocator(staging);
    }

    const ncnn::VulkanDevice* device;
    ncnn::VkAllocator* blob;
    ncnn::VkAllocator* staging;
    ncnn::Net net;
    ncnn::Mat first, second, timestep;
    std::unique_ptr<jint[]> pixels;
    int pixel_capacity = 0;
};

struct ScopedPath {
    JNIEnv* env;
    jstring string;
    const char* value;
    ScopedPath(JNIEnv* env, jstring string)
        : env(env), string(string), value(env->GetStringUTFChars(string, nullptr)) {}
    ~ScopedPath() { if (value) env->ReleaseStringUTFChars(string, value); }
};

bool load_input(JNIEnv* env, jintArray pixels, int width, int height, int padded_width, int padded_height,
    jint* scratch, ncnn::Mat& rgb) {
    env->GetIntArrayRegion(pixels, 0, width * height, scratch);
    if (env->ExceptionCheck()) return false;
    rgb.create(padded_width, padded_height, 3);
    if (rgb.empty()) return false;
    for (int channel = 0; channel < 3; ++channel) {
        auto plane = rgb.channel(channel);
        plane.fill(0.f);
        for (int y = 0; y < height; ++y) {
            float* target = plane.row(y);
            const jint* source = scratch + y * width;
            for (int x = 0; x < width; ++x)
                target[x] = ((static_cast<unsigned>(source[x]) >> (16 - channel * 8)) & 255u) * (1.f / 255.f);
        }
    }
    return true;
}

unsigned channel_byte(float normalized) {
    return static_cast<unsigned>(std::lround(std::clamp(normalized, 0.f, 1.f) * 255.f));
}
}

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mangaflow_enhancement_engine_AndroidRifeVulkanJni_open(
    JNIEnv* env, jobject, jstring graph, jstring weights) {
    ScopedPath params(env, graph);
    ScopedPath coefficients(env, weights);
    if (!params.value || !coefficients.value) return 0;

    const auto* device = mangaflow_vulkan_device();
    if (!device) return 0;
    auto model = std::unique_ptr<Model>(new (std::nothrow) Model(device));
    if (!model || !model->blob || !model->staging) return 0;
    model->net.opt.use_vulkan_compute = true;
    model->net.opt.use_fp16_packed = false;
    model->net.opt.use_fp16_storage = false;
    model->net.opt.use_fp16_arithmetic = false;
    model->net.opt.use_fp16_uniform = false;
    model->net.opt.use_int8_storage = false;
    model->net.opt.use_int8_arithmetic = false;
    model->net.opt.use_int8_inference = false;
    model->net.opt.num_threads = 1;
    model->net.set_vulkan_device(device);
    if (model->net.register_custom_layer("rife.Warp", create_warp) != 0 ||
        model->net.load_param(params.value) != 0 || model->net.load_model(coefficients.value) != 0) return 0;
    for (const auto* layer : model->net.layers()) {
        if (!layer || (!layer->support_vulkan && layer->type != "Input" && layer->type != "Split")) return 0;
    }
    return reinterpret_cast<jlong>(model.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mangaflow_enhancement_engine_AndroidRifeVulkanJni_infer(
    JNIEnv* env, jobject, jlong handle, jintArray previous, jintArray next, jint width, jint height,
    jfloat timestep, jintArray destination) {
    if (!handle || width <= 0 || height <= 0 || width > kMaximumSide || height > kMaximumSide ||
        width * height > kMaximumPixels || !(timestep > 0.f && timestep < 1.f)) return -1;
    const int pixels = width * height;
    if (env->GetArrayLength(previous) != pixels || env->GetArrayLength(next) != pixels ||
        env->GetArrayLength(destination) != pixels) return -1;

    auto* model = reinterpret_cast<Model*>(handle);
    const int padded_width = (width + 31) / 32 * 32;
    const int padded_height = (height + 31) / 32 * 32;
    if (pixels > model->pixel_capacity) {
        auto replacement = std::unique_ptr<jint[]>(new (std::nothrow) jint[pixels]);
        if (!replacement) return -2;
        model->pixels = std::move(replacement);
        model->pixel_capacity = pixels;
    }
    if (!load_input(env, previous, width, height, padded_width, padded_height, model->pixels.get(), model->first) ||
        !load_input(env, next, width, height, padded_width, padded_height, model->pixels.get(), model->second)) return -2;
    model->timestep.create(padded_width, padded_height, 1);
    if (model->timestep.empty()) return -2;
    model->timestep.fill(timestep);

    auto options = model->net.opt;
    options.blob_vkallocator = model->blob;
    options.workspace_vkallocator = model->blob;
    options.staging_vkallocator = model->staging;
    ncnn::VkMat first_gpu, second_gpu, timestep_gpu, output_gpu;
    ncnn::Mat output;
    ncnn::VkCompute commands(model->device);
    commands.record_upload(model->first, first_gpu, options);
    commands.record_upload(model->second, second_gpu, options);
    commands.record_upload(model->timestep, timestep_gpu, options);
    if (first_gpu.empty() || second_gpu.empty() || timestep_gpu.empty()) return -2;

    auto extractor = model->net.create_extractor();
    extractor.set_blob_vkallocator(model->blob);
    extractor.set_workspace_vkallocator(model->blob);
    extractor.set_staging_vkallocator(model->staging);
    if (extractor.input("in0", first_gpu) != 0 || extractor.input("in1", second_gpu) != 0 ||
        extractor.input("in2", timestep_gpu) != 0 || extractor.extract("out0", output_gpu, commands) != 0 ||
        output_gpu.empty()) return -3;
    commands.record_download(output_gpu, output, options);
    if (commands.submit_and_wait() != 0) return -3;

    if (output.empty() || output.dims != 3 || output.c != 3 || output.elempack != 1 || output.elemsize != 4u ||
        output.w != padded_width || output.h != padded_height) return -4;
    const float* red = output.channel(0);
    const float* green = output.channel(1);
    const float* blue = output.channel(2);
    for (int y = 0; y < height; ++y) {
        for (int x = 0; x < width; ++x) {
            const int i = y * padded_width + x;
            if (!std::isfinite(red[i]) || !std::isfinite(green[i]) || !std::isfinite(blue[i])) return -4;
            model->pixels[y * width + x] = static_cast<jint>(0xff000000u |
                channel_byte(red[i]) << 16 | channel_byte(green[i]) << 8 | channel_byte(blue[i]));
        }
    }
    env->SetIntArrayRegion(destination, 0, pixels, model->pixels.get());
    return env->ExceptionCheck() ? -1 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mangaflow_enhancement_engine_AndroidRifeVulkanJni_close(JNIEnv*, jobject, jlong handle) {
    delete reinterpret_cast<Model*>(handle);
}
