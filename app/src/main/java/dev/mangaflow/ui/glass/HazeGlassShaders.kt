// Copyright 2025, Christopher Banes and the Haze project contributors
// SPDX-License-Identifier: Apache-2.0
//
// Adapted from Haze Glass 2.1.0 GlassShaders.kt, commit
// ca21ff2060e52e1882afdb94f9b055f7fc834bd9.
// Fixed to one rounded rectangle, Circle profile and simple chroma sampling;
// retains the upstream edge-refraction, normals, Fresnel, colour and rim formulas.
// Android depth/blur bindings and source differences: third_party/haze-glass/ADAPTATION.md.
package dev.mangaflow.ui.glass

internal const val HazeOpticalShader = """
uniform shader content;
uniform float2 sampleSize;
uniform float2 materialOrigin;
uniform float2 materialSize;
uniform float sampleStep;
uniform float refractionStrength;
uniform float ambientResponse;
uniform float edgeSoftness;
uniform float refractionHeight;
uniform float edgeRefractionWidth;
uniform float chromaticAberrationStrength;
uniform vec4 cornerRadii;
layout(color) uniform vec4 tintColor;
const float surfaceProfile = 0.0;
uniform float contrast;
uniform float whitePoint;
uniform float chromaMultiplier;
uniform float refractionScale;
uniform float contentNormalBlend;
uniform float fresnelExponent;
uniform float2 interactionPosition;
uniform float interactionRadius;
uniform float engagement;

vec2 clampSample(vec2 coord) {
  return clamp(coord, vec2(0.5), sampleSize - vec2(0.5));
}
vec2 clampMaterial(vec2 coord) {
  return clamp(coord, vec2(0.0), materialSize);
}
vec4 sampleDepth(vec2 coord) {
  return content.eval(clampSample(coord));
}

    float shapeCoverage(float sd, float pixelWidth) {
      // Pixel coverage is independent of the authored optical edge softness and density.
      // sampleStep is two physical pixels expressed in the current working coordinates.
      // Keep the transition inside the existing silhouette, including caller-applied clips.
      return clamp(-sd / max(pixelWidth, 0.0001), 0.0, 1.0);
    }

    float smootherstep(float x) {
      float t = clamp(x, 0.0, 1.0);
      return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    float sdRectangle(vec2 localCoord, vec2 size) {
      vec2 halfSize = size * 0.5;
      vec2 edgeDistance = abs(localCoord - halfSize) - halfSize;
      float outside = length(max(edgeDistance, 0.0));
      float inside = min(max(edgeDistance.x, edgeDistance.y), 0.0);
      return outside + inside;
    }

    float sdRoundedRect(vec2 localCoord, vec2 size, vec4 radii) {
      float sd = sdRectangle(localCoord, size);
      if (localCoord.x < radii.x && localCoord.y < radii.x) {
        sd = max(sd, length(localCoord - vec2(radii.x)) - radii.x);
      }
      if (localCoord.x > size.x - radii.y && localCoord.y < radii.y) {
        sd = max(sd, length(localCoord - vec2(size.x - radii.y, radii.y)) - radii.y);
      }
      if (localCoord.x > size.x - radii.z && localCoord.y > size.y - radii.z) {
        sd = max(
          sd,
          length(localCoord - vec2(size.x - radii.z, size.y - radii.z)) - radii.z
        );
      }
      if (localCoord.x < radii.w && localCoord.y > size.y - radii.w) {
        sd = max(sd, length(localCoord - vec2(radii.w, size.y - radii.w)) - radii.w);
      }
      return sd;
    }

    vec2 safeNormalize(vec2 value, vec2 fallback) {
      float len = length(value);
      return len > 0.0001 ? value / len : fallback;
    }
    vec4 reversedSmootherstep(vec4 t) {
      t = clamp(t, 0.0, 1.0);
      return vec4(1.0) - t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    vec2 gradSdRectangle(vec2 localCoord, vec2 size, float blendWidth) {
      vec4 edgeDistance = vec4(
        -localCoord.x,
        localCoord.x - size.x,
        -localCoord.y,
        localCoord.y - size.y
      );
      float maxDistance = max(
        max(edgeDistance.x, edgeDistance.y),
        max(edgeDistance.z, edgeDistance.w)
      );
      vec4 weights = reversedSmootherstep(
        (vec4(maxDistance) - edgeDistance) / blendWidth
      );
      float totalWeight = dot(weights, vec4(1.0));
      return vec2(weights.y - weights.x, weights.w - weights.z) /
        max(totalWeight, 0.0001);
    }

    vec2 gradSdRoundedRect(vec2 localCoord, vec2 size, vec4 radii, float blendWidth) {
      vec2 rectangleGradient = gradSdRectangle(localCoord, size, blendWidth);
      vec2 topLeftDelta = min(localCoord - vec2(radii.x), vec2(0.0));
      vec2 topRightDelta = vec2(
        max(localCoord.x - (size.x - radii.y), 0.0),
        min(localCoord.y - radii.y, 0.0)
      );
      vec2 bottomRightDelta = max(
        localCoord - vec2(size.x - radii.z, size.y - radii.z),
        vec2(0.0)
      );
      vec2 bottomLeftDelta = vec2(
        min(localCoord.x - radii.w, 0.0),
        max(localCoord.y - (size.y - radii.w), 0.0)
      );
      vec4 cornerWeights = vec4(
        smootherstep(clamp(length(topLeftDelta) / max(radii.x, 0.0001), 0.0, 1.0)),
        smootherstep(clamp(length(topRightDelta) / max(radii.y, 0.0001), 0.0, 1.0)),
        smootherstep(clamp(length(bottomRightDelta) / max(radii.z, 0.0001), 0.0, 1.0)),
        smootherstep(clamp(length(bottomLeftDelta) / max(radii.w, 0.0001), 0.0, 1.0))
      );
      vec2 cornerGradient = safeNormalize(
        safeNormalize(topLeftDelta, rectangleGradient) * cornerWeights.x +
          safeNormalize(topRightDelta, rectangleGradient) * cornerWeights.y +
          safeNormalize(bottomRightDelta, rectangleGradient) * cornerWeights.z +
          safeNormalize(bottomLeftDelta, rectangleGradient) * cornerWeights.w,
        rectangleGradient
      );
      float cornerWeight = max(
        max(cornerWeights.x, cornerWeights.y),
        max(cornerWeights.z, cornerWeights.w)
      );
      return mix(rectangleGradient, cornerGradient, cornerWeight);
    }

    float circleMap(float x) {
      return 1.0 - sqrt(max(0.0, 1.0 - x * x));
    }

    float squircleMap(float t) {
      return 1.0 - smootherstep(t * sqrt(t));
    }

    float evaluateProfile(float t) {
      float x = 1.0 - clamp(t, 0.0, 1.0);
      if (surfaceProfile == 1) {
        return squircleMap(t);
      } else if (surfaceProfile == 2) {
        return -circleMap(x);
      } else if (surfaceProfile == 3) {
        float convex = circleMap(x);
        float concave = -circleMap(x);
        float blend = smootherstep(clamp(t / 0.7, 0.0, 1.0));
        return mix(convex, concave, blend);
      }
      return circleMap(x);
    }

    float opticalFieldWeight(vec2 size, float height) {
      float shortestSide = max(min(size.x, size.y), 0.0001);
      float aspectRatio = max(size.x, size.y) / shortestSide;
      float elongation = smootherstep(clamp((aspectRatio - 1.5) / 1.5, 0.0, 1.0));
      float overlap = smootherstep(clamp((height / (shortestSide * 0.5) - 0.75) / 0.25, 0.0, 1.0));
      return elongation * overlap;
    }

    float opticalFieldWeight() { return opticalFieldWeight(materialSize, refractionHeight); }

    vec2 normalizedMaterialCoord(vec2 localCoord, vec2 size) {
      vec2 halfSize = max(size * 0.5, vec2(0.0001));
      return (localCoord - halfSize) / halfSize;
    }

    float domeDistance(vec2 localCoord, vec2 size, float height) {
      vec2 normalized = normalizedMaterialCoord(localCoord, size);
      vec2 squared = normalized * normalized;
      float radius = sqrt(clamp(squared.x + squared.y - squared.x * squared.y, 0.0, 1.0));
      return max((1.0 - radius) * height, 0.0);
    }

    float opticalDistance(vec2 p, float sd, float weight, vec2 size, float height) {
      float edge = max(-sd, 0.0);
      if (weight <= 0.0) return edge;
      return mix(edge, domeDistance(p, size, height), weight);
    }

    float opticalDistanceFromSignedDistance(vec2 p, float sd, float weight) {
      return opticalDistance(p, sd, weight, materialSize, refractionHeight);
    }

    float surfaceHeightFromDistance(float distance, float height) {
      float zone = max(height, 0.0001);
      return evaluateProfile(clamp(distance / zone, 0.0, 1.0)) * zone;
    }

    float surfaceHeightFromOpticalDistance(float distance) {
      return surfaceHeightFromDistance(distance, refractionHeight);
    }

    float rectSurfaceHeight(vec2 p, vec2 size, vec4 radii, float height, float weight) {
      if (weight >= 1.0) return surfaceHeightFromDistance(domeDistance(p, size, height), height);
      float sd = sdRoundedRect(p, size, radii);
      return surfaceHeightFromDistance(opticalDistance(p, sd, weight, size, height), height);
    }

    float surfaceHeight(vec2 p, float weight) {
      if (weight >= 1.0) return surfaceHeightFromOpticalDistance(domeDistance(p, materialSize, refractionHeight));
      float sd = sdRoundedRect(p, materialSize, cornerRadii);
      return surfaceHeightFromOpticalDistance(opticalDistanceFromSignedDistance(p, sd, weight));
    }

    float surfaceHeightNormFromOpticalDistance(float distance) {
      return clamp(surfaceHeightFromOpticalDistance(distance) / max(refractionHeight, 0.0001), -1.0, 1.0);
    }

    vec2 rectOpticalGradient(vec2 p, vec2 size, vec4 radii, float height, float weight) {
      vec2 normalized = normalizedMaterialCoord(p, size);
      vec2 squared = normalized * normalized;
      vec2 dome = vec2(normalized.x * (1.0 - squared.y), normalized.y * (1.0 - squared.x));
      if (weight >= 1.0) return dome;
      vec2 boundary = gradSdRoundedRect(p, size, radii, max(height, 1.0));
      return mix(boundary, dome, weight);
    }

    vec2 opticalSurfaceGradient(vec2 p, float weight) {
      return rectOpticalGradient(p, materialSize, cornerRadii, refractionHeight, weight);
    }

    vec2 edgeRefractionGradient(vec2 localCoord, vec2 size, vec4 customRadii) {
      // The cap keeps the optical corner regions disjoint, allowing one analytic arc normal.
      vec4 radii = min(customRadii * 1.5, vec4(min(size.x, size.y) * 0.5));
      vec2 delta = vec2(0.0);
      bool corner = false;
      if (localCoord.x < radii.x && localCoord.y < radii.x) {
        delta = localCoord - vec2(radii.x);
        corner = true;
      } else if (localCoord.x > size.x - radii.y && localCoord.y < radii.y) {
        delta = localCoord - vec2(size.x - radii.y, radii.y);
        corner = true;
      } else if (localCoord.x > size.x - radii.z &&
          localCoord.y > size.y - radii.z) {
        delta = localCoord - vec2(size.x - radii.z, size.y - radii.z);
        corner = true;
      } else if (localCoord.x < radii.w && localCoord.y > size.y - radii.w) {
        delta = localCoord - vec2(radii.w, size.y - radii.w);
        corner = true;
      }
      float blendWidth = max(sampleStep * 0.5, 0.01);
      if (!corner) return gradSdRectangle(localCoord, size, blendWidth);
      float cornerDistance = length(delta);
      float axisDistance = min(abs(delta.x), abs(delta.y));
      if (axisDistance >= blendWidth) return delta / cornerDistance;
      // Join the straight-edge field at both corner axes, including their undefined arc centre.
      // Away from this one-sample transition the normal is the exact analytic radial direction.
      float cornerWeight = smootherstep(clamp(axisDistance / blendWidth, 0.0, 1.0));
      return mix(
        gradSdRectangle(localCoord, size, blendWidth),
        delta / max(cornerDistance, 0.0001),
        cornerWeight
      );
    }


    vec2 surfaceGradient(vec2 localCoord, float fieldWeight) {
      float left = surfaceHeight(
        clampMaterial(localCoord - vec2(sampleStep, 0.0)),
        fieldWeight
      );
      float right = surfaceHeight(
        clampMaterial(localCoord + vec2(sampleStep, 0.0)),
        fieldWeight
      );
      float up = surfaceHeight(
        clampMaterial(localCoord - vec2(0.0, sampleStep)),
        fieldWeight
      );
      float down = surfaceHeight(
        clampMaterial(localCoord + vec2(0.0, sampleStep)),
        fieldWeight
      );
      return vec2(right - left, down - up) * (0.5 / max(sampleStep, 0.0001));
    }

    vec2 surfaceLightingGradient(vec2 localCoord, float sd, float fieldWeight) {
      if (surfaceProfile == 1) {
        float refractionZone = max(refractionHeight, 0.0001);
        float opticalDistance = opticalDistanceFromSignedDistance(localCoord, sd, fieldWeight);
        float t = clamp(opticalDistance / refractionZone, 0.0, 1.0);
        float lightingSlope = 2.0 * (1.0 - smootherstep(t));
        return opticalSurfaceGradient(localCoord, fieldWeight) * lightingSlope;
      }
      return surfaceGradient(localCoord, fieldWeight);
    }

    vec3 unpremultiply(vec4 color) {
      return color.a > 0.0001 ? color.rgb / color.a : vec3(0.0);
    }

    vec3 applyAmbient(vec3 color, float ambient) {
      // Bound generated SDR highlights before coverage/alpha, while preserving the existing
      // response of colour-managed tints with legitimate extended-range components.
      bool inputIsSdr = min(color.r, min(color.g, color.b)) >= 0.0 &&
        max(color.r, max(color.g, color.b)) <= 1.0;
      vec3 litColor = color * ambient;
      return inputIsSdr ? clamp(litColor, 0.0, 1.0) : litColor;
    }

    vec4 premultiply(vec3 color, float alpha) {
      return vec4(color * alpha, alpha);
    }

    float luma(vec3 color) {
      return dot(color, vec3(0.299, 0.587, 0.114));
    }

    vec3 sampleChromaSimple(
      vec2 coord,
      vec2 chromaOffset,
      vec4 centerSample
    ) {
      if (length(chromaOffset) < 0.0001) return unpremultiply(centerSample);
      vec3 forward = unpremultiply(sampleDepth(coord + chromaOffset));
      vec3 backward = unpremultiply(sampleDepth(coord - chromaOffset));
      vec3 centerStraight = unpremultiply(centerSample);
      return vec3(forward.r, centerStraight.g, backward.b);
    }

    vec3 computeContentNormal(vec2 coord, vec4 centerSample) {
      float center = luma(unpremultiply(centerSample));
      float right = luma(
        unpremultiply(sampleDepth(coord + vec2(sampleStep, 0.0)))
      );
      float down = luma(
        unpremultiply(sampleDepth(coord + vec2(0.0, sampleStep)))
      );
      vec2 gradient =
        vec2(right - center, down - center) / max(sampleStep, 0.0001);
      return normalize(vec3(gradient, 1.0));
    }

    vec3 applyColorGrading(vec3 color, float appliedWhitePoint) {
      if (chromaMultiplier != 1.0) {
        vec3 linearColor = toLinearSrgb(color);
        float luminance = dot(linearColor, vec3(0.2126, 0.7152, 0.0722));
        float chromaScale = chromaMultiplier;
        if (chromaMultiplier > 1.0) {
          bool sourceIsInSrgbGamut =
            min(linearColor.r, min(linearColor.g, linearColor.b)) >= 0.0 &&
              max(linearColor.r, max(linearColor.g, linearColor.b)) <= 1.0;
          if (sourceIsInSrgbGamut) {
            vec3 delta = linearColor - vec3(luminance);
            if (delta.r > 0.0) chromaScale = min(chromaScale, (1.0 - luminance) / delta.r);
            if (delta.r < 0.0) chromaScale = min(chromaScale, -luminance / delta.r);
            if (delta.g > 0.0) chromaScale = min(chromaScale, (1.0 - luminance) / delta.g);
            if (delta.g < 0.0) chromaScale = min(chromaScale, -luminance / delta.g);
            if (delta.b > 0.0) chromaScale = min(chromaScale, (1.0 - luminance) / delta.b);
            if (delta.b < 0.0) chromaScale = min(chromaScale, -luminance / delta.b);
            chromaScale = max(1.0, chromaScale);
          } else {
            chromaScale = 1.0;
          }
        }
        color = fromLinearSrgb(mix(vec3(luminance), linearColor, chromaScale));
      }
      if (appliedWhitePoint != 0.0) {
        vec3 target = appliedWhitePoint > 0.0 ? vec3(1.0) : vec3(0.0);
        color = mix(color, target, abs(appliedWhitePoint));
      }
      if (contrast != 0.0) {
        color = clamp((color - 0.5) * (1.0 + contrast) + 0.5, 0.0, 1.0);
      }
      return color;
    }
vec2 edgeDisplacement(vec2 coord, float distanceToEdge, float multiplier) {
  if (edgeRefractionWidth <= 0.0 || distanceToEdge >= edgeRefractionWidth) return vec2(0.0);
  float t = clamp(distanceToEdge / edgeRefractionWidth, 0.0, 1.0);
  float cutoff = max(1.0 - t * t, 0.0);
  float profile = exp(-t / 0.225) * cutoff * cutoff;
  float strength = clamp(refractionStrength * multiplier, 0.0, 1.0);
  float magnitude = profile * min(strength * refractionScale, min(materialSize.x, materialSize.y) * 0.5);
  vec2 gradient = edgeRefractionGradient(coord, materialSize, cornerRadii);
  float gradientLength = length(gradient);
  float centerFade = smootherstep(clamp(gradientLength / 0.5, 0.0, 1.0));
  return -gradient / max(gradientLength, 0.0001) * centerFade * magnitude;
}

vec4 main(vec2 coord) {
  vec2 localCoord = coord - materialOrigin;
  vec2 halfSize = materialSize * 0.5;
  vec2 centeredCoord = localCoord - halfSize;
  float sd = sdRoundedRect(localCoord, materialSize, cornerRadii);
  float coverage = shapeCoverage(sd, sampleStep * 0.5);
  if (coverage <= 0.0) return vec4(0.0);

  float distanceToEdge = max(-sd, 0.0);
  float shapeMask = edgeSoftness <= 0.0 ? 1.0
    : smootherstep(clamp(distanceToEdge / max(edgeSoftness, 0.0001), 0.0, 1.0));
  float normalizedInteraction = distance(localCoord, interactionPosition) / max(interactionRadius, 0.0001);
  float interactionWeight = engagement * (1.0 - smootherstep(clamp(normalizedInteraction, 0.0, 1.0)));
  vec2 displacement = edgeDisplacement(localCoord, distanceToEdge, 1.0 + 0.15 * interactionWeight);
  vec2 refractCoord = clampSample(coord + displacement);
  float cornerWeight = abs((centeredCoord.x * centeredCoord.y) / max(halfSize.x * halfSize.y, 0.001));
  vec2 chromaOffset = displacement * chromaticAberrationStrength * 0.5 * cornerWeight;
  vec4 centerSample = sampleDepth(refractCoord);
  vec3 straightColor = sampleChromaSimple(refractCoord, chromaOffset, centerSample);

  float ambient = 1.0;
  if (ambientResponse > 0.0) {
    float fieldWeight = opticalFieldWeight();
    vec2 gradient = surfaceLightingGradient(localCoord, sd, fieldWeight);
    vec3 shapeNormal = normalize(vec3(-gradient.x, -gradient.y, 1.0));
    vec3 normal = shapeNormal;
    if (contentNormalBlend > 0.0) {
      normal = normalize(mix(shapeNormal, computeContentNormal(refractCoord, centerSample), contentNormalBlend));
    }
    float fresnelBase = 1.0 - max(dot(normal, vec3(0.0, 0.0, 1.0)), 0.0);
    float fresnel = fresnelExponent == 3.0 ? fresnelBase * fresnelBase * fresnelBase
      : pow(fresnelBase, fresnelExponent);
    ambient = mix(1.0, 1.0 + fresnel, clamp(ambientResponse, 0.0, 1.0));
  }

  // Preserve the app's bounded readability response on contrasting manga artwork.
  // Adapt the optical white point, without painting a separate inner plate.
  float backdropLuma = dot(straightColor, vec3(0.2126, 0.7152, 0.0722));
  float adaptedWhitePoint = whitePoint >= 0.0
    ? mix(whitePoint, max(whitePoint, 0.55), 1.0 - smoothstep(0.15, 0.55, backdropLuma))
    : mix(whitePoint, min(whitePoint, -0.62), smoothstep(0.4, 0.85, backdropLuma));
  vec3 graded = clamp(applyColorGrading(straightColor,
    clamp(adaptedWhitePoint + 0.025 * interactionWeight, -1.0, 1.0)), 0.0, 1.0);
  vec3 tinted = mix(graded, tintColor.rgb, tintColor.a);
  vec3 lit = applyAmbient(tinted, ambient);
  // Same localized white-light composition as Haze's interaction-light stage.
  lit = mix(lit, vec3(1.0), interactionWeight * shapeMask * 0.32);
  vec4 processed = premultiply(lit, centerSample.a);
  if (shapeMask < 1.0) processed = mix(sampleDepth(coord), processed, shapeMask);
  return processed.a > 0.0 ? processed * coverage : vec4(0.0);
}
"""

internal const val HazeRimShader = """
uniform float2 materialSize;
uniform float sampleStep;
uniform vec4 cornerRadii;
uniform float specularIntensity;
layout(color) uniform vec4 edgeShadow;
uniform float specularExponent;
uniform float edgeSoftness;
uniform float2 lightPosition;

vec2 clampMaterial(vec2 coord) {
  return clamp(coord, vec2(0.0), materialSize);
}

    float shapeCoverage(float sd, float pixelWidth) {
      // Pixel coverage is independent of the authored optical edge softness and density.
      // sampleStep is two physical pixels expressed in the current working coordinates.
      // Keep the transition inside the existing silhouette, including caller-applied clips.
      return clamp(-sd / max(pixelWidth, 0.0001), 0.0, 1.0);
    }

    float smootherstep(float x) {
      float t = clamp(x, 0.0, 1.0);
      return t * t * t * (t * (t * 6.0 - 15.0) + 10.0);
    }

    float sdRectangle(vec2 localCoord, vec2 size) {
      vec2 halfSize = size * 0.5;
      vec2 edgeDistance = abs(localCoord - halfSize) - halfSize;
      float outside = length(max(edgeDistance, 0.0));
      float inside = min(max(edgeDistance.x, edgeDistance.y), 0.0);
      return outside + inside;
    }

    float sdRoundedRect(vec2 localCoord, vec2 size, vec4 radii) {
      float sd = sdRectangle(localCoord, size);
      if (localCoord.x < radii.x && localCoord.y < radii.x) {
        sd = max(sd, length(localCoord - vec2(radii.x)) - radii.x);
      }
      if (localCoord.x > size.x - radii.y && localCoord.y < radii.y) {
        sd = max(sd, length(localCoord - vec2(size.x - radii.y, radii.y)) - radii.y);
      }
      if (localCoord.x > size.x - radii.z && localCoord.y > size.y - radii.z) {
        sd = max(
          sd,
          length(localCoord - vec2(size.x - radii.z, size.y - radii.z)) - radii.z
        );
      }
      if (localCoord.x < radii.w && localCoord.y > size.y - radii.w) {
        sd = max(sd, length(localCoord - vec2(radii.w, size.y - radii.w)) - radii.w);
      }
      return sd;
    }

    vec2 safeNormalize(vec2 value, vec2 fallback) {
      float len = length(value);
      return len > 0.0001 ? value / len : fallback;
    }
float materialSdf(vec2 coord) {
  return sdRoundedRect(coord, materialSize, cornerRadii);
}
vec2 sdfGradient(vec2 coord) {
  float left = materialSdf(clampMaterial(coord - vec2(sampleStep, 0.0)));
  float right = materialSdf(clampMaterial(coord + vec2(sampleStep, 0.0)));
  float up = materialSdf(clampMaterial(coord - vec2(0.0, sampleStep)));
  float down = materialSdf(clampMaterial(coord + vec2(0.0, sampleStep)));
  return vec2(right - left, down - up) * (0.5 / max(sampleStep, 0.0001));
}
vec4 main(vec2 coord) {
  float sd = materialSdf(coord);
  float coverage = shapeCoverage(sd, sampleStep * 0.5);
  if (coverage <= 0.0) return vec4(0.0);
  float edgeWidth = max(edgeSoftness, sampleStep);
  float edge = 1.0 - smootherstep(clamp(-sd / max(edgeWidth, 0.0001), 0.0, 1.0));
  if (edge <= 0.0) return vec4(0.0);
  vec2 gradient = sdfGradient(coord);
  vec3 normal = normalize(vec3(-gradient.x, -gradient.y, 1.0));
  vec2 lightDirection2D = safeNormalize(lightPosition - coord, vec2(0.0, -1.0));
  vec3 lightDirection = normalize(vec3(lightDirection2D, 1.0));
  float specularBase = max(dot(normal, lightDirection), 0.0);
  float specular = pow(specularBase, specularExponent);
  float specularAlpha = specular * specularIntensity * edge;
  float shadowAlpha = edgeShadow.a * edge;
  float alpha = specularAlpha + shadowAlpha * (1.0 - specularAlpha);
  vec3 color = vec3(specularAlpha) + edgeShadow.rgb * shadowAlpha * (1.0 - specularAlpha);
  return alpha > 0.0 ? vec4(color, alpha) * coverage : vec4(0.0);
}
"""
