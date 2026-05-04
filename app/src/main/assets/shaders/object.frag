precision mediump float;

uniform sampler2D u_Texture;
uniform vec4 u_LightingParameters;
uniform vec4 u_MaterialParameters;
uniform vec4 u_ColorCorrectionParameters;
uniform vec4 u_ObjColor;

varying vec3 v_ViewPosition;
varying vec3 v_ViewNormal;
varying vec2 v_TexCoord;

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
}
