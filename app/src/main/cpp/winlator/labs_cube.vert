#version 450
layout(push_constant) uniform Cube {
    vec4 rotation;
    vec4 config; // x: single-cube preview, y: fragment work, z: grid columns
} pc;
layout(location = 0) out vec3 color;
layout(location = 1) out vec3 worldNormal;

const vec2 corners[6] = vec2[6](
    vec2(-1,-1), vec2(1,-1), vec2(1,1),
    vec2(-1,-1), vec2(1,1), vec2(-1,1)
);
const vec3 palette[6] = vec3[6](
    vec3(.24,.67,1.0), vec3(.93,.38,.65), vec3(.90,.76,.29),
    vec3(.28,.87,.69), vec3(.73,.52,.98), vec3(.99,.52,.31)
);

void main() {
    int face = gl_VertexIndex / 6;
    vec2 uv = corners[gl_VertexIndex % 6];
    vec3 p;
    vec3 n;
    if (face == 0) { p = vec3(uv, 1); n = vec3(0,0,1); }
    else if (face == 1) { p = vec3(-uv, -1); n = vec3(0,0,-1); }
    else if (face == 2) { p = vec3(uv.x,1,-uv.y); n = vec3(0,1,0); }
    else if (face == 3) { p = vec3(uv.x,-1,uv.y); n = vec3(0,-1,0); }
    else if (face == 4) { p = vec3(1,uv); n = vec3(1,0,0); }
    else { p = vec3(-1,uv.x,-uv.y); n = vec3(-1,0,0); }

    float cy = pc.rotation.x, sy = pc.rotation.y;
    float cx = pc.rotation.z, sx = pc.rotation.w;
    p = vec3(cy*p.x + sy*p.z, p.y, -sy*p.x + cy*p.z);
    p = vec3(p.x, cx*p.y - sx*p.z, sx*p.y + cx*p.z);

    if (pc.config.x > 0.5) {
        // A proper, large rotating 3D cube preview. Never measured.
        p *= 0.72;
        float depth = p.z + 3.4;
        gl_Position = vec4(p.x * 1.2, -p.y * 1.2,
            clamp((depth - 0.8) / 4.2, 0.0, 1.0) * depth, depth);
    } else {
        int idx = gl_InstanceIndex;
        int cols = max(1, int(pc.config.z));
        int rows = int(ceil(float(int(pc.config.w)) / float(cols)));
        float gx = float(idx % cols) - (float(cols) - 1.0) * 0.5;
        float gy = float(idx / cols) - (float(rows) - 1.0) * 0.5;
        // Fixed fill area and cube sizes for all drivers.
        float scale = min(0.75 / float(cols), 0.75 / float(rows));
        p *= scale;
        p += vec3(gx * 1.68 / float(cols), gy * 1.68 / float(rows),
                  float((idx * 7) % 13) * 0.003);
        gl_Position = vec4(p.x, -p.y, 0.45 + p.z * 0.14, 1.0);
    }
    color = palette[face] * (0.76 + float(gl_InstanceIndex % 5) * 0.045);
    worldNormal = n;
}
