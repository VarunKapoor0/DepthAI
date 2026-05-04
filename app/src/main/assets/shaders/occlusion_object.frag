precision mediump float;

uniform sampler2D u_Texture;
uniform vec4 u_LightingParameters;
uniform vec4 u_MaterialParameters;
uniform vec4 u_ColorCorrectionParameters;
uniform vec4 u_ObjColor;

varying vec3 v_ViewPosition;
varying vec3 v_ViewNormal;
varying vec2 v_TexCoord;
varying vec3 v_ScreenSpacePosition;

uniform sampler2D u_Depth;
uniform mat3 u_UvTransform;
uniform float u_DepthTolerancePerMm;
uniform float u_OcclusionAlpha;
uniform float u_DepthAspectRatio;

float GetDepthMillimeters(in vec2 depth_uv) {
    vec3 packedDepthAndVisibility = texture2D(u_Depth, depth_uv).xyz;
    return dot(packedDepthAndVisibility.xy, vec2(255.0, 256.0 * 255.0));
}

float InverseLerp(in float value, in float min_bound, in float max_bound) {
    return clamp((value - min_bound) / (max_bound - min_bound), 0.0, 1.0);
}

float GetVisibility(in vec2 depth_uv, in float asset_depth_mm) {
    float depth_mm = GetDepthMillimeters(depth_uv);
    float visibility_occlusion = clamp(0.5 * (depth_mm - asset_depth_mm) /
                                       (u_DepthTolerancePerMm * asset_depth_mm) + 0.5, 0.0, 1.0);
    float visibility_depth_near = 1.0 - InverseLerp(depth_mm, 150.0, 200.0);
    float visibility_depth_far = InverseLerp(depth_mm, 17500.0, 20000.0);
    return max(max(visibility_occlusion, u_OcclusionAlpha),
               max(visibility_depth_near, visibility_depth_far));
}

void main() {
    const float kGamma = 0.4545454;
    const float kInverseGamma = 2.2;
    const float kMiddleGrayGamma = 0.466;

    vec3 viewLightDirection = u_LightingParameters.xyz;
    vec3 colorShift = u_ColorCorrectionParameters.rgb;
    float averagePixelIntensity = u_ColorCorrectionParameters.a;

    float materialAmbient = u_MaterialParameters.x;
    float materialDiffuse = u_MaterialParameters.y;
    float materialSpecular = u_MaterialParameters.z;
    float materialSpecularPower = u_MaterialParameters.w;

    vec3 viewFragmentDirection = normalize(v_ViewPosition);
    vec3 viewNormal = normalize(v_ViewNormal);

    vec4 objectColor = texture2D(u_Texture, vec2(v_TexCoord.x, 1.0 - v_TexCoord.y));

    if (u_ObjColor.a >= 255.0) {
        float intensity = objectColor.r;
        objectColor.rgb = u_ObjColor.rgb * intensity / 255.0;
    }

    objectColor.rgb = pow(objectColor.rgb, vec3(kInverseGamma));

    float ambient = materialAmbient;
    float diffuse = materialDiffuse * 0.5 * (dot(viewNormal, viewLightDirection) + 1.0);
    vec3 reflectedLightDirection = reflect(viewLightDirection, viewNormal);
    float specularStrength = max(0.0, dot(viewFragmentDirection, reflectedLightDirection));
    float specular = materialSpecular * pow(specularStrength, materialSpecularPower);

    vec3 color = objectColor.rgb * (ambient + diffuse) + specular;
    color.rgb = pow(color, vec3(kGamma));
    color *= colorShift * (averagePixelIntensity / kMiddleGrayGamma);
    gl_FragColor.rgb = color;
    gl_FragColor.a = objectColor.a;

    const float kMToMm = 1000.0;
    float asset_depth_mm = v_ViewPosition.z * kMToMm * -1.;
    vec2 depth_uvs = (u_UvTransform * vec3(v_ScreenSpacePosition.xy, 1)).xy;
    gl_FragColor.a *= GetVisibility(depth_uvs, asset_depth_mm);
}
