#version 120

uniform vec2 rectSize;
uniform vec4 color1, color2, color3, color4;
uniform float radius;
uniform float alp;

#define NOISE .5/255.0

// 四个角做双线性插值，alpha 通道一起插。
//
// 这里以前只插 rgb（形参是 vec3、调用处传的是 colorN.rgb），输出 alpha 直接取全局的 alp，
// 而 SharedRenderingConstants.roundedRectGradient* 走的重载把 alp 写死成 1.0。结果是
// 「传进来的每一个半透明颜色都被画成完全不透明的实色」——液态玻璃的反光层、灵动岛进度条、
// 主题调色板轨道全部受影响，玻璃看起来是一块盖死的色板而不是一层反光。
vec4 createGradient(vec2 coords, vec4 c1, vec4 c2, vec4 c3, vec4 c4){
    vec4 color = mix(mix(c1, c2, coords.y), mix(c3, c4, coords.y), coords.x);
    // 抖动只加在颜色上，不要动 alpha：给 alpha 加噪点会在大面积低透明度的层上出现颗粒。
    color.rgb += mix(NOISE, -NOISE, fract(sin(dot(coords.xy, vec2(12.9898, 78.233))) * 43758.5453));
    return color;
}

void main() {
    vec2 st = gl_TexCoord[0].st;

    // 计算纹理坐标相对于中心的位置
    vec2 center = gl_TexCoord[0].st * rectSize - rectSize * 0.5;
    // 计算边缘距离
    float distance = length(max(abs(center) - (rectSize * 0.5 - radius - 1.0), 0.0)) - radius;
    // 使用smoothstep进行抗锯齿
    float edge = 1.0 - smoothstep(0.0, 1.0, distance);

    vec4 gradient = createGradient(st, color1, color2, color3, color4);
    gl_FragColor = vec4(gradient.rgb, gradient.a * alp * edge);
}
