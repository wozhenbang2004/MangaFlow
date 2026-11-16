// Copyright 2021 Yi Xie
//
// Licensed under the Apache License, Version 2.0 (the "License");
// you may not use this file except in compliance with the License.
// You may obtain a copy of the License at
//
//     http://www.apache.org/licenses/LICENSE-2.0
//
// Unless required by applicable law or agreed to in writing, software
// distributed under the License is distributed on an "AS IS" BASIS,
// WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
// See the License for the specific language governing permissions and
// limitations under the License.
//
// MangaFlow adaptation of imxieyi/Anime4KMetal's Shared/Anime4K.swift,
// c46b8652a203d259ffbc3d39f21aa54263e11584. Changes: source strings supplied
// by the shared pinned library, immutable plans, checked errors, one in-flight
// texture arena with lifetime reuse, and a FIT/FILL/ZOOM presentation pass.
// No weights or intermediate CNN values are replaced by a sharpening filter.

import Foundation
import Metal

fileprivate struct Anime4KCompiledPass {
    let shader: Anime4KMetalShader
    let pipeline: MTLComputePipelineState
}

fileprivate struct Anime4KPlannedPass {
    let pipeline: MTLComputePipelineState
    let inputs: [Int]
    let output: Int
}

final class Anime4KMetalProgram {
    private let groups: [[Anime4KCompiledPass]]
    fileprivate let sampler: MTLSamplerState
    fileprivate let presentation: MTLComputePipelineState

    init(device: MTLDevice, sources: [String]) throws {
        guard sources.count == 2 else { throw Anime4KMetalError.failed("Anime4K CNN 组合不完整") }
        groups = try sources.map { source in
            try Anime4KMetalShader.parse(source).map { shader in
                let library = try device.makeLibrary(source: shader.metalSource(), options: nil)
                guard let function = library.makeFunction(name: "anime4kPass") else {
                    throw Anime4KMetalError.failed("Anime4K Metal 函数缺失")
                }
                return Anime4KCompiledPass(shader: shader,
                    pipeline: try device.makeComputePipelineState(function: function))
            }
        }
        let descriptor = MTLSamplerDescriptor()
        descriptor.minFilter = .linear
        descriptor.magFilter = .linear
        descriptor.sAddressMode = .clampToEdge
        descriptor.tAddressMode = .clampToEdge
        guard let sampler = device.makeSamplerState(descriptor: descriptor) else {
            throw Anime4KMetalError.unavailable("当前 GPU 无法创建 Anime4K 采样器")
        }
        self.sampler = sampler
        let displayLibrary = try device.makeLibrary(source: Self.presentationSource, options: nil)
        guard let function = displayLibrary.makeFunction(name: "anime4kPresent") else {
            throw Anime4KMetalError.failed("Anime4K 显示函数缺失")
        }
        presentation = try device.makeComputePipelineState(function: function)
    }

    func makePlan(device: MTLDevice, input: Anime4KTextureSize,
                  output: Anime4KTextureSize, scale: String) throws -> Anime4KMetalPlan {
        guard input.width > 0, input.height > 0, output.width > 0, output.height > 0 else {
            throw Anime4KMetalError.unavailable("当前视频还没有可用的画面尺寸")
        }
        let xScale = Double(output.width) / Double(input.width)
        let yScale = Double(output.height) / Double(input.height)
        let factor = scale == "ZOOM" ? max(xScale, yScale) : min(xScale, yScale)
        let content = scale == "FILL" ? output : Anime4KTextureSize(
            width: max(1, Int((Double(input.width) * factor).rounded())),
            height: max(1, Int((Double(input.height) * factor).rounded())))

        // -1 is the CVMetalTexture-backed input; every other resource is one
        // pass result. MAIN advances only when the shader actually saves MAIN.
        var resourceSizes: [Anime4KTextureSize] = []
        var pending: [Anime4KPlannedPass] = []
        var main = -1
        for group in groups {
            var names: [String: Int] = ["MAIN": main, "NATIVE": -1, "HOOKED": main]
            for pass in group {
                var sizes = names.mapValues { $0 == -1 ? input : resourceSizes[$0] }
                sizes["OUTPUT"] = content
                if let condition = pass.shader.condition, try condition.evaluate(sizes) == 0 { continue }
                guard let widthExpression = pass.shader.width, let heightExpression = pass.shader.height else {
                    throw Anime4KMetalError.failed("Anime4K pass 缺少尺寸")
                }
                let width = try widthExpression.evaluate(sizes)
                let height = try heightExpression.evaluate(sizes)
                // Apple GPU family 3+ supports 16384 px 2D textures. Do not
                // silently reduce the user's selected profile on a real limit.
                guard width >= 1, height >= 1, width <= 16_384, height <= 16_384,
                      width.rounded() == width, height.rounded() == height else {
                    throw Anime4KMetalError.unavailable("Anime4K 输出超过当前 GPU 纹理尺寸能力")
                }
                let inputs = try pass.shader.inputNames.map { name -> Int in
                    guard let reference = names[name] else {
                        throw Anime4KMetalError.failed("Anime4K pass 缺少输入纹理")
                    }
                    return reference
                }
                let result = resourceSizes.count
                resourceSizes.append(Anime4KTextureSize(width: Int(width), height: Int(height)))
                pending.append(Anime4KPlannedPass(pipeline: pass.pipeline, inputs: inputs, output: result))
                names[pass.shader.outputName] = result
                if pass.shader.outputName == "MAIN" { names["HOOKED"] = result }
            }
            guard let result = names["MAIN"] else { throw Anime4KMetalError.failed("Anime4K 未输出主画面") }
            main = result
        }
        guard main >= 0, !pending.isEmpty else {
            throw Anime4KMetalError.failed("Anime4K 没有可执行的 CNN pass")
        }

        // A medium profile needs several live convolution results. Reuse only
        // after the last consumer has encoded, never for an input/output alias.
        var lastUse = [Int](repeating: 0, count: resourceSizes.count)
        for (index, pass) in pending.enumerated() {
            lastUse[pass.output] = max(lastUse[pass.output], index)
            for reference in pass.inputs where reference >= 0 { lastUse[reference] = max(lastUse[reference], index) }
        }
        lastUse[main] = pending.count
        var resourceSlots: [Int: Int] = [:]
        var slotSizes: [Anime4KTextureSize] = []
        var slotLastUse: [Int] = []
        var planned: [Anime4KPlannedPass] = []
        for (index, pass) in pending.enumerated() {
            let size = resourceSizes[pass.output]
            let slot: Int
            if let reusable = slotSizes.indices.first(where: { slotSizes[$0] == size && slotLastUse[$0] < index }) {
                slot = reusable
                slotLastUse[slot] = lastUse[pass.output]
            } else {
                slot = slotSizes.count
                slotSizes.append(size)
                slotLastUse.append(lastUse[pass.output])
            }
            let inputSlots = try pass.inputs.map { reference -> Int in
                if reference == -1 { return -1 }
                guard let assigned = resourceSlots[reference] else {
                    throw Anime4KMetalError.failed("Anime4K 纹理生命周期不完整")
                }
                return assigned
            }
            resourceSlots[pass.output] = slot
            planned.append(Anime4KPlannedPass(pipeline: pass.pipeline, inputs: inputSlots, output: slot))
        }
        guard let finalSlot = resourceSlots[main] else { throw Anime4KMetalError.failed("Anime4K 最终纹理缺失") }
        let textures = try slotSizes.map { size -> MTLTexture in
            let descriptor = MTLTextureDescriptor.texture2DDescriptor(pixelFormat: .rgba16Float,
                width: size.width, height: size.height, mipmapped: false)
            descriptor.storageMode = .private
            descriptor.usage = [.shaderRead, .shaderWrite]
            guard let texture = device.makeTexture(descriptor: descriptor) else {
                throw Anime4KMetalError.unavailable("当前 GPU 无法分配 Anime4K 浮点纹理，继续使用原画")
            }
            return texture
        }
        return Anime4KMetalPlan(program: self, passes: planned, textures: textures,
            finalSlot: finalSlot, inputSize: input, outputSize: output, contentSize: content,
            processedSize: resourceSizes[main],
            upscaled: resourceSizes[main].width > input.width || resourceSizes[main].height > input.height,
            scale: scale)
    }

    private static let presentationSource = """
    #include <metal_stdlib>
    using namespace metal;
    kernel void anime4kPresent(texture2d<float, access::sample> input [[texture(0)]],
                              texture2d<float, access::write> output [[texture(1)]],
                              sampler textureSampler [[sampler(0)]],
                              constant uint &scaleMode [[buffer(0)]],
                              uint2 gid [[thread_position_in_grid]]) {
        if (gid.x >= output.get_width() || gid.y >= output.get_height()) return;
        float2 sourceSize(input.get_width(), input.get_height());
        float2 outputSize(output.get_width(), output.get_height());
        float2 pixel = float2(gid) + float2(0.5);
        float2 position = pixel / outputSize;
        if (scaleMode != 1) {
            float2 ratios = outputSize / sourceSize;
            float factor = scaleMode == 2 ? max(ratios.x, ratios.y) : min(ratios.x, ratios.y);
            float2 contentSize = sourceSize * factor;
            float2 offset = (outputSize - contentSize) * 0.5;
            position = (pixel - offset) / contentSize;
        }
        if (any(position < float2(0.0)) || any(position > float2(1.0))) {
            output.write(float4(0.0, 0.0, 0.0, 1.0), gid);
            return;
        }
        // CNN intermediates retain signed FP16 values. Clamp only at this SDR
        // display boundary; residual-network alpha is not a display alpha.
        output.write(float4(clamp(input.sample(textureSampler, position).rgb,
                                  float3(0.0), float3(1.0)), 1.0), gid);
    }
    """
}

final class Anime4KMetalPlan {
    let inputSize: Anime4KTextureSize
    let outputSize: Anime4KTextureSize
    let contentSize: Anime4KTextureSize
    /** The actual final CNN MAIN texture, never the viewport/letterbox size. */
    let processedSize: Anime4KTextureSize
    let upscaled: Bool
    let scale: String
    private let program: Anime4KMetalProgram
    private let passes: [Anime4KPlannedPass]
    private let textures: [MTLTexture]
    private let finalSlot: Int

    fileprivate init(program: Anime4KMetalProgram, passes: [Anime4KPlannedPass], textures: [MTLTexture],
                     finalSlot: Int, inputSize: Anime4KTextureSize, outputSize: Anime4KTextureSize,
                     contentSize: Anime4KTextureSize, processedSize: Anime4KTextureSize, upscaled: Bool, scale: String) {
        self.program = program
        self.passes = passes
        self.textures = textures
        self.finalSlot = finalSlot
        self.inputSize = inputSize
        self.outputSize = outputSize
        self.contentSize = contentSize
        self.processedSize = processedSize
        self.upscaled = upscaled
        self.scale = scale
    }

    func encode(commandBuffer: MTLCommandBuffer, input: MTLTexture, output: MTLTexture) throws {
        guard input.width == inputSize.width, input.height == inputSize.height,
              output.width == outputSize.width, output.height == outputSize.height else {
            throw Anime4KMetalError.failed("Anime4K 画面尺寸在提交前已变化")
        }
        for pass in passes {
            guard let encoder = commandBuffer.makeComputeCommandEncoder() else {
                throw Anime4KMetalError.failed("Anime4K 无法创建计算命令")
            }
            encoder.setComputePipelineState(pass.pipeline)
            encoder.setSamplerState(program.sampler, index: 0)
            for (index, slot) in pass.inputs.enumerated() {
                encoder.setTexture(slot == -1 ? input : textures[slot], index: index)
            }
            let destination = textures[pass.output]
            encoder.setTexture(destination, index: pass.inputs.count)
            dispatch(encoder, pipeline: pass.pipeline, texture: destination)
            encoder.endEncoding()
        }
        guard let encoder = commandBuffer.makeComputeCommandEncoder() else {
            throw Anime4KMetalError.failed("Anime4K 无法创建显示命令")
        }
        encoder.setComputePipelineState(program.presentation)
        encoder.setSamplerState(program.sampler, index: 0)
        encoder.setTexture(textures[finalSlot], index: 0)
        encoder.setTexture(output, index: 1)
        var mode: UInt32 = scale == "FILL" ? 1 : scale == "ZOOM" ? 2 : 0
        encoder.setBytes(&mode, length: MemoryLayout<UInt32>.size, index: 0)
        dispatch(encoder, pipeline: program.presentation, texture: output)
        encoder.endEncoding()
    }

    private func dispatch(_ encoder: MTLComputeCommandEncoder, pipeline: MTLComputePipelineState, texture: MTLTexture) {
        let width = min(16, pipeline.threadExecutionWidth)
        let height = max(1, min(16, pipeline.maxTotalThreadsPerThreadgroup / width))
        encoder.dispatchThreadgroups(MTLSize(width: (texture.width + width - 1) / width,
                                            height: (texture.height + height - 1) / height, depth: 1),
                                     threadsPerThreadgroup: MTLSize(width: width, height: height, depth: 1))
    }
}

/** Only compilation and texture allocation use this serial worker, never UIKit. */
final class Anime4KMetalCompiler {
    static let shared = Anime4KMetalCompiler()
    let queue = DispatchQueue(label: "dev.mangaflow.anime4k.compile", qos: .userInitiated)
    private var cached: [(sources: [String], device: UInt64, program: Anime4KMetalProgram)] = []

    func compile(device: MTLDevice, sources: [String], completion: @escaping (Result<Anime4KMetalProgram, Error>) -> Void) {
        queue.async {
            let result: Result<Anime4KMetalProgram, Error>
            if let hit = self.cached.first(where: { $0.device == device.registryID && $0.sources == sources }) {
                result = .success(hit.program)
            } else {
                result = Result { try Anime4KMetalProgram(device: device, sources: sources) }
                if case .success(let program) = result {
                    if self.cached.count >= 2 { self.cached.removeFirst() }
                    self.cached.append((sources, device.registryID, program))
                }
            }
            DispatchQueue.main.async { completion(result) }
        }
    }
}
