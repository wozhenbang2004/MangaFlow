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
// MangaFlow adaptation of imxieyi/Anime4KMetal's MPVShader.swift,
// c46b8652a203d259ffbc3d39f21aa54263e11584. Changes: strict parsing for the
// four pinned CNN shaders, checked RPN expressions, pixel-centre sampling,
// bounds checks, and errors instead of force unwraps. This is not a general
// GLSL compiler; unsupported shader syntax must keep the original player.

import Foundation

enum Anime4KMetalError: Error, LocalizedError {
    case unavailable(String)
    case failed(String)

    var errorDescription: String? {
        switch self {
        case .unavailable(let message), .failed(let message): return message
        }
    }
}

struct Anime4KTextureSize: Equatable {
    let width: Int
    let height: Int
}

struct Anime4KExpression {
    let tokens: [String]

    init(_ source: String) throws {
        tokens = source.split(whereSeparator: { $0.isWhitespace }).map(String.init)
        guard !tokens.isEmpty else {
            throw Anime4KMetalError.failed("Anime4K 尺寸表达式为空")
        }
    }

    func evaluate(_ sizes: [String: Anime4KTextureSize]) throws -> Double {
        var values: [Double] = []
        for token in tokens {
            if let number = Double(token), number.isFinite {
                values.append(number)
                continue
            }
            let dimension = token.split(separator: ".")
            if dimension.count == 2, let size = sizes[String(dimension[0])] {
                if dimension[1] == "w" { values.append(Double(size.width)); continue }
                if dimension[1] == "h" { values.append(Double(size.height)); continue }
            }
            guard ["+", "-", "*", "/", "<", ">", "="].contains(token), values.count >= 2 else {
                throw Anime4KMetalError.failed("Anime4K 不支持的尺寸表达式")
            }
            let right = values.removeLast()
            let left = values.removeLast()
            let result: Double
            switch token {
            case "+": result = left + right
            case "-": result = left - right
            case "*": result = left * right
            case "/":
                guard right != 0 else { throw Anime4KMetalError.failed("Anime4K 尺寸除数为零") }
                result = left / right
            case "<": result = left < right ? 1 : 0
            case ">": result = left > right ? 1 : 0
            default: result = left == right ? 1 : 0
            }
            guard result.isFinite else { throw Anime4KMetalError.failed("Anime4K 尺寸计算失败") }
            values.append(result)
        }
        guard values.count == 1, let result = values.first else {
            throw Anime4KMetalError.failed("Anime4K 尺寸表达式不完整")
        }
        return result
    }
}

struct Anime4KMetalShader {
    let name: String
    var hook: String?
    var binds: [String] = []
    var save: String?
    var width: Anime4KExpression?
    var height: Anime4KExpression?
    var condition: Anime4KExpression?
    var code: [String] = []

    var inputNames: [String] {
        binds.contains("MAIN") ? binds : binds + ["MAIN"]
    }

    var outputName: String { save ?? "MAIN" }

    func metalSource() throws -> String {
        let originalBody = code.joined(separator: "\n")
        // The checked-in S/M Restore and Upscale files each contain one hook
        // function per pass. A changed upstream language must fail explicitly.
        guard hook == "MAIN", !binds.isEmpty,
              originalBody.components(separatedBy: "vec4 hook() {").count == 2 else {
            throw Anime4KMetalError.failed("Anime4K shader 语法与当前 Metal 适配器不兼容")
        }
        let textures = inputNames.enumerated().map {
            "texture2d<float, access::sample> \($0.element) [[texture(\($0.offset))]]"
        }
        let hookTextures = inputNames.map { "texture2d<float, access::sample> \($0)" }
        let arguments = (["float2 mtlPos", "sampler textureSampler"] + hookTextures).joined(separator: ", ")
        let body = originalBody.replacingOccurrences(of: "vec4 hook() {", with: "float4 anime4kHook(\(arguments)) {")
        let macros = inputNames.map { name in
            """
            #define \(name)_pos mtlPos
            #define \(name)_size float2(\(name).get_width(), \(name).get_height())
            #define \(name)_pt (float2(1.0) / \(name)_size)
            #define \(name)_tex(pos) \(name).sample(textureSampler, pos)
            #define \(name)_texOff(off) \(name)_tex(\(name)_pos + \(name)_pt * float2(off))
            """
        }.joined(separator: "\n")
        let entryArguments = (textures + [
            "texture2d<float, access::write> output [[texture(\(inputNames.count))]]",
            "sampler textureSampler [[sampler(0)]]",
            "uint2 gid [[thread_position_in_grid]]",
        ]).joined(separator: ", ")
        let callArguments = (["mtlPos", "textureSampler"] + inputNames).joined(separator: ", ")
        return """
        #include <metal_stdlib>
        using namespace metal;
        using vec2 = float2;
        using vec3 = float3;
        using vec4 = float4;
        using ivec2 = int2;
        using mat4 = float4x4;
        \(macros)
        \(body)
        kernel void anime4kPass(\(entryArguments)) {
            if (gid.x >= output.get_width() || gid.y >= output.get_height()) return;
            float2 mtlPos = (float2(gid) + float2(0.5)) /
                float2(output.get_width(), output.get_height());
            output.write(anime4kHook(\(callArguments)), gid);
        }
        """
    }

    static func parse(_ source: String) throws -> [Anime4KMetalShader] {
        var result: [Anime4KMetalShader] = []
        var current: Anime4KMetalShader?
        for rawLine in source.split(separator: "\n", omittingEmptySubsequences: false) {
            let line = rawLine.trimmingCharacters(in: .whitespacesAndNewlines)
            if line.isEmpty { continue }
            if line.hasPrefix("//!") {
                let directive = String(line.dropFirst(3))
                let pieces = directive.split(maxSplits: 1, whereSeparator: { $0.isWhitespace }).map(String.init)
                guard pieces.count == 2 else {
                    throw Anime4KMetalError.failed("Anime4K shader 指令不完整")
                }
                let value = pieces[1]
                if pieces[0] == "DESC" {
                    if let pass = current { result.append(pass) }
                    current = Anime4KMetalShader(name: value)
                    continue
                }
                guard var pass = current else {
                    throw Anime4KMetalError.failed("Anime4K shader 缺少 pass 描述")
                }
                switch pieces[0] {
                case "HOOK": pass.hook = value
                case "BIND":
                    guard identifier(value), !pass.binds.contains(value) else {
                        throw Anime4KMetalError.failed("Anime4K 纹理绑定无效")
                    }
                    pass.binds.append(value)
                case "SAVE":
                    guard identifier(value) else { throw Anime4KMetalError.failed("Anime4K 纹理名称无效") }
                    pass.save = value
                case "WIDTH": pass.width = try Anime4KExpression(value)
                case "HEIGHT": pass.height = try Anime4KExpression(value)
                case "WHEN": pass.condition = try Anime4KExpression(value)
                case "COMPONENTS":
                    guard let components = Int(value), (1...4).contains(components) else {
                        throw Anime4KMetalError.failed("Anime4K 纹理分量无效")
                    }
                default: throw Anime4KMetalError.failed("Anime4K shader 含有未适配的指令")
                }
                current = pass
            } else if !line.hasPrefix("//") {
                guard var pass = current else {
                    throw Anime4KMetalError.failed("Anime4K shader 缺少 pass")
                }
                pass.code.append(line)
                current = pass
            }
        }
        if let pass = current { result.append(pass) }
        guard !result.isEmpty, result.count <= 32 else {
            throw Anime4KMetalError.failed("Anime4K pass 数量无效")
        }
        for pass in result {
            guard pass.hook == "MAIN", pass.inputNames.count <= 12, !pass.code.isEmpty,
                  pass.width != nil, pass.height != nil else {
                throw Anime4KMetalError.failed("Anime4K pass 不完整")
            }
        }
        return result
    }

    private static func identifier(_ value: String) -> Bool {
        guard let first = value.unicodeScalars.first,
              CharacterSet.letters.union(CharacterSet(charactersIn: "_")).contains(first) else { return false }
        return value.unicodeScalars.allSatisfy {
            CharacterSet.alphanumerics.union(CharacterSet(charactersIn: "_")).contains($0)
        }
    }
}
