#version 450
layout(push_constant) uniform Cube {
    vec4 rotation;
    vec4 config; // y = requested fragment iterations
} pc;
layout(location = 0) in vec3 color;
layout(location = 1) in vec3 worldNormal;
layout(location = 0) out vec4 outColor;

void main() {
    vec3 v = color * 0.75 + abs(worldNormal) * 0.18;
    vec2 t = gl_FragCoord.xy * 0.00137;
    // Explicitly separated fixed GPU loads, not a single oversized stress case.
    int iterations = clamp(int(pc.config.y), 0, 8);
    for (int i = 0; i < iterations; ++i) {
        t = fract(t * vec2(1.137, 1.219) + vec2(0.071, 0.037));
        v = fract(v * 1.173 + vec3(t.x, t.y, t.x*t.y) * 0.273);
        v = mix(v, v.yzx, 0.23);
    }
    outColor = vec4(color * (0.73 + 0.27 * v), 1.0);
}
