// Real-CUGAN NOSE model input/output and padding follow nihui/realcugan-ncnn-vulkan
// 395302c5c70f1bff604c974e92e0a87e45c9f9ee. See the accompanying MIT notice.
#include <jni.h>
#include <algorithm>
#include <cmath>
#include <memory>
#include <mutex>
#include <new>
#include <vector>
#include <command.h>
#include <gpu.h>
#include <layer.h>
#include <net.h>

namespace {
constexpr int kPadding = 18;
constexpr int kTileLimit = 96;

struct ScopedString {
    JNIEnv* env;
    jstring text;
    const char* value;
    ScopedString(JNIEnv* env, jstring text) : env(env), text(text), value(env->GetStringUTFChars(text, nullptr)) {}
    ~ScopedString() { if (value) env->ReleaseStringUTFChars(text, value); }
};

struct Model {
    explicit Model(const ncnn::VulkanDevice* device) : device(device) {}
    const ncnn::VulkanDevice* device;
    ncnn::Net net;
};

struct Allocators {
    explicit Allocators(const ncnn::VulkanDevice* device)
        : device(device), blob(device->acquire_blob_allocator()), staging(device->acquire_staging_allocator()) {}
    ~Allocators() {
        if (blob) device->reclaim_blob_allocator(blob);
        if (staging) device->reclaim_staging_allocator(staging);
    }
    const ncnn::VulkanDevice* device;
    ncnn::VkAllocator* blob;
    ncnn::VkAllocator* staging;
};

// Other readers may start after a cancellation. The GPU instance is process-owned.
const ncnn::VulkanDevice* hardware_device() {
    static std::once_flag once;
    static const ncnn::VulkanDevice* selected = nullptr;
    std::call_once(once, [] {
        if (ncnn::create_gpu_instance() != 0) return;
        const int count = ncnn::get_gpu_count();
        const auto* preferred = count > 0 ? ncnn::get_gpu_device(ncnn::get_default_gpu_index()) : nullptr;
        if (preferred && preferred->is_valid() && (preferred->info.type() == 0 || preferred->info.type() == 1)) {
            selected = preferred;
            return;
        }
        for (int i = 0; i < count; ++i) {
            const auto* device = ncnn::get_gpu_device(i);
            if (device && device->is_valid() && (device->info.type() == 0 || device->info.type() == 1)) {
                selected = device;
                return;
            }
        }
    });
    return selected;
}

int channel_byte(float value) {
    return static_cast<int>(std::lround(std::clamp(value, 0.0f, 1.0f) * 255.0f));
}
}

const ncnn::VulkanDevice* mangaflow_vulkan_device() { return hardware_device(); }

extern "C" JNIEXPORT jlong JNICALL
Java_dev_mangaflow_enhancement_engine_AndroidCuganVulkanJni_open(
    JNIEnv* env, jobject, jstring param, jstring weights) {
    ScopedString graph(env, param);
    ScopedString data(env, weights);
    if (!graph.value || !data.value) return 0;
    const auto* device = hardware_device();
    if (!device) return 0;
    auto model = std::unique_ptr<Model>(new (std::nothrow) Model(device));
    if (!model) return 0;
    model->net.opt.use_vulkan_compute = true;
    model->net.opt.use_fp16_packed = false;
    model->net.opt.use_fp16_storage = false;
    model->net.opt.use_fp16_arithmetic = false;
    model->net.opt.use_fp16_uniform = false;
    model->net.opt.use_int8_inference = false;
    model->net.opt.use_int8_storage = false;
    model->net.opt.use_int8_arithmetic = false;
    model->net.opt.num_threads = 1;
    model->net.set_vulkan_device(device);
    if (model->net.load_param(graph.value) != 0 || model->net.load_model(data.value) != 0) return 0;
    for (const auto* layer : model->net.layers()) {
        if (!layer || (!layer->support_vulkan && layer->type != "Input" && layer->type != "Split")) return 0;
    }
    return reinterpret_cast<jlong>(model.release());
}

extern "C" JNIEXPORT jint JNICALL
Java_dev_mangaflow_enhancement_engine_AndroidCuganVulkanJni_process(
    JNIEnv* env, jobject, jlong pointer, jintArray pixels, jintArray result, jint width, jint height) {
    if (!pointer || width <= 0 || width > kTileLimit || height <= 0 || height > kTileLimit) return -1;
    const int input_width = width + (width % 2) + kPadding * 2;
    const int input_height = height + (height % 2) + kPadding * 2;
    const int result_width = width * 2;
    const int result_height = height * 2;
    if (env->GetArrayLength(pixels) != input_width * input_height ||
        env->GetArrayLength(result) != result_width * result_height) return -1;

    auto* model = reinterpret_cast<Model*>(pointer);
    Allocators allocators(model->device);
    if (!allocators.blob || !allocators.staging) return -2;
    ncnn::Option options = model->net.opt;
    options.blob_vkallocator = allocators.blob;
    options.workspace_vkallocator = allocators.blob;
    options.staging_vkallocator = allocators.staging;

    std::vector<jint> argb(input_width * input_height);
    env->GetIntArrayRegion(pixels, 0, static_cast<jsize>(argb.size()), argb.data());
    if (env->ExceptionCheck()) return -1;
    ncnn::Mat input(input_width, input_height, 3, 4u, 1);
    if (input.empty()) return -2;
    for (int c = 0; c < 3; ++c) {
        float* plane = input.channel(c);
        for (size_t i = 0; i < argb.size(); ++i)
            plane[i] = (static_cast<unsigned>(argb[i]) >> (16 - 8 * c) & 255) * (1.0f / 255.0f);
    }

    ncnn::VkMat gpu_input, gpu_output;
    ncnn::Mat cpu_output;
    ncnn::VkCompute command(model->device);
    command.record_upload(input, gpu_input, options);
    if (gpu_input.empty()) return -2;
    auto extractor = model->net.create_extractor();
    extractor.set_blob_vkallocator(allocators.blob);
    extractor.set_workspace_vkallocator(allocators.blob);
    extractor.set_staging_vkallocator(allocators.staging);
    if (extractor.input("in0", gpu_input) != 0 || extractor.extract("out0", gpu_output, command) != 0 ||
        gpu_output.empty()) return -3;
    command.record_download(gpu_output, cpu_output, options);
    if (command.submit_and_wait() != 0) return -3;

    if (cpu_output.empty() || cpu_output.dims != 3 || cpu_output.elempack != 1 || cpu_output.elemsize != 4u ||
        cpu_output.c != 3 || cpu_output.w != (width + width % 2) * 2 ||
        cpu_output.h != (height + height % 2) * 2) return -4;

    const float* red = cpu_output.channel(0);
    const float* green = cpu_output.channel(1);
    const float* blue = cpu_output.channel(2);
    std::vector<jint> enhanced(result_width * result_height);
    for (int y = 0; y < result_height; ++y) {
        for (int x = 0; x < result_width; ++x) {
            const int index = y * cpu_output.w + x;
            if (!std::isfinite(red[index]) || !std::isfinite(green[index]) || !std::isfinite(blue[index])) return -4;
            enhanced[y * result_width + x] = static_cast<jint>(0xff000000u |
                (channel_byte(red[index]) << 16) | (channel_byte(green[index]) << 8) | channel_byte(blue[index]));
        }
    }
    env->SetIntArrayRegion(result, 0, static_cast<jsize>(enhanced.size()), enhanced.data());
    return env->ExceptionCheck() ? -1 : 0;
}

extern "C" JNIEXPORT void JNICALL
Java_dev_mangaflow_enhancement_engine_AndroidCuganVulkanJni_close(JNIEnv*, jobject, jlong pointer) {
    delete reinterpret_cast<Model*>(pointer);
}
