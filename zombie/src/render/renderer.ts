// WebGL renderer + post-processing chain (GTAO → bloom → tone map → cinematic grade).
import * as THREE from 'three';
import { EffectComposer } from 'three/examples/jsm/postprocessing/EffectComposer.js';
import { RenderPass } from 'three/examples/jsm/postprocessing/RenderPass.js';
import { UnrealBloomPass } from 'three/examples/jsm/postprocessing/UnrealBloomPass.js';
import { OutputPass } from 'three/examples/jsm/postprocessing/OutputPass.js';
import { ShaderPass } from 'three/examples/jsm/postprocessing/ShaderPass.js';
import { GTAOPass } from 'three/examples/jsm/postprocessing/GTAOPass.js';

export type Quality = 'low' | 'medium' | 'high' | 'ultra';
export interface QualityCfg { pixelRatio: number; shadow: number; shadowExtent: number; ao: boolean; bloom: boolean; msaa: number; grass: number; far: number; trees: number; tex: 'low' | 'medium' | 'high' }
export const QUALITY: Record<Quality, QualityCfg> = {
  low: { pixelRatio: 0.75, shadow: 1024, shadowExtent: 45, ao: false, bloom: false, msaa: 0, grass: 0.35, far: 320, trees: 0.6, tex: 'low' },
  medium: { pixelRatio: 1, shadow: 2048, shadowExtent: 60, ao: false, bloom: true, msaa: 2, grass: 0.65, far: 480, trees: 0.85, tex: 'medium' },
  high: { pixelRatio: 1.25, shadow: 4096, shadowExtent: 75, ao: true, bloom: true, msaa: 4, grass: 1, far: 650, trees: 1, tex: 'medium' },
  ultra: { pixelRatio: 2, shadow: 4096, shadowExtent: 90, ao: true, bloom: true, msaa: 4, grass: 1.5, far: 850, trees: 1, tex: 'high' },
};

const GradeShader = {
  uniforms: {
    tDiffuse: { value: null as THREE.Texture | null },
    uTime: { value: 0 }, uVignette: { value: 0.32 }, uGrain: { value: 0.045 }, uSat: { value: 0.86 }, uContrast: { value: 1.08 },
    uLift: { value: new THREE.Vector3(0.01, 0.012, 0.02) }, uGain: { value: new THREE.Vector3(1.02, 1.0, 0.96) },
    uDamage: { value: 0 }, uLowHealth: { value: 0 }, uBlur: { value: 0 }, uChroma: { value: 0.0012 }, uFade: { value: 0 },
    uPanic: { value: 0 }, uSick: { value: 0 }, uRes: { value: new THREE.Vector2(1, 1) },
  },
  vertexShader: `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }`,
  fragmentShader: `
uniform sampler2D tDiffuse; uniform float uTime, uVignette, uGrain, uSat, uContrast, uDamage, uLowHealth, uBlur, uChroma, uFade, uPanic, uSick;
uniform vec3 uLift, uGain; uniform vec2 uRes; varying vec2 vUv;
float hash(vec2 p){ return fract(sin(dot(p, vec2(12.9898,78.233))) * 43758.5453); }
void main(){
  vec2 uv = vUv;
  // sickness wobble
  uv += vec2(sin(uv.y*9.0+uTime*1.3), cos(uv.x*7.0+uTime*1.1)) * 0.004 * uSick;
  vec2 dc = uv - 0.5;
  float ca = uChroma + uDamage*0.006 + uPanic*0.003;
  vec3 col;
  col.r = texture2D(tDiffuse, uv + dc*ca).r;
  col.g = texture2D(tDiffuse, uv).g;
  col.b = texture2D(tDiffuse, uv - dc*ca).b;
  // radial blur (pain / exhaustion / panic)
  float bl = uBlur + uPanic*0.3;
  if (bl > 0.01) {
    vec3 acc = col; float w = 1.0;
    for (int i = 1; i < 7; i++) { float s = float(i) / 6.0 * 0.02 * bl; acc += texture2D(tDiffuse, uv - dc * s).rgb; w += 1.0; }
    col = acc / w;
  }
  // lift / gain / contrast / saturation
  col = col * uGain + uLift * (1.0 - col);
  col = (col - 0.5) * uContrast + 0.5;
  float l = dot(col, vec3(0.2126,0.7152,0.0722));
  float sat = uSat * (1.0 - uLowHealth*0.75);
  col = mix(vec3(l), col, sat);
  // split tone: cool shadows, warm highlights
  col += vec3(-0.012, 0.0, 0.02) * (1.0 - l) + vec3(0.02, 0.01, -0.015) * l;
  // damage flash and low health pulse
  float pulse = 0.5 + 0.5*sin(uTime*5.0);
  float edge = smoothstep(0.25, 0.85, length(dc)*1.4);
  col = mix(col, vec3(0.5,0.0,0.0), edge * (uDamage*0.7 + uLowHealth*0.35*pulse));
  // vignette
  float v = smoothstep(0.85, 0.2, length(dc) * (1.0 + uVignette));
  col *= mix(1.0 - uVignette, 1.0, v);
  // film grain
  float g = hash(uv * uRes + fract(uTime*13.37)*100.0) - 0.5;
  col += g * uGrain * (1.2 - l);
  col = mix(col, vec3(0.0), uFade);
  gl_FragColor = vec4(clamp(col, 0.0, 1.0), 1.0);
}`,
};

export class Renderer {
  renderer: THREE.WebGLRenderer;
  composer!: EffectComposer;
  grade!: ShaderPass;
  bloom: UnrealBloomPass | null = null;
  gtao: GTAOPass | null = null;
  scenePass!: RenderPass;
  viewPass!: RenderPass;
  cfg: QualityCfg;

  constructor(public canvas: HTMLCanvasElement, public quality: Quality) {
    this.cfg = QUALITY[quality];
    this.renderer = new THREE.WebGLRenderer({ canvas, antialias: false, powerPreference: 'high-performance', stencil: false });
    const r = this.renderer;
    r.setPixelRatio(Math.min(window.devicePixelRatio, this.cfg.pixelRatio));
    r.setSize(window.innerWidth, window.innerHeight);
    r.shadowMap.enabled = true;
    r.shadowMap.type = THREE.PCFShadowMap;
    r.toneMapping = THREE.ACESFilmicToneMapping;
    r.toneMappingExposure = 1.0;
    r.outputColorSpace = THREE.SRGBColorSpace;
  }

  setup(scene: THREE.Scene, camera: THREE.PerspectiveCamera, viewScene: THREE.Scene, viewCam: THREE.PerspectiveCamera) {
    const r = this.renderer; const w = window.innerWidth, h = window.innerHeight;
    const rt = new THREE.WebGLRenderTarget(w, h, { type: THREE.HalfFloatType, samples: this.cfg.msaa });
    this.composer = new EffectComposer(r, rt);
    this.composer.setPixelRatio(r.getPixelRatio());
    this.scenePass = new RenderPass(scene, camera);
    this.composer.addPass(this.scenePass);
    if (this.cfg.ao) {
      this.gtao = new GTAOPass(scene, camera, w, h);
      this.gtao.output = GTAOPass.OUTPUT.Default;
      this.gtao.blendIntensity = 0.85;
      this.gtao.updateGtaoMaterial({ radius: 0.6, distanceExponent: 1.4, thickness: 1.5, scale: 1.0, samples: 12 });
      this.gtao.updatePdMaterial({ lumaPhi: 10, depthPhi: 2, normalPhi: 3, radius: 6, rings: 2, samples: 12 });
      this.composer.addPass(this.gtao);
    }
    // first person viewmodel drawn on top with its own depth
    this.viewPass = new RenderPass(viewScene, viewCam);
    this.viewPass.clear = false; this.viewPass.clearDepth = true;
    this.composer.addPass(this.viewPass);
    if (this.cfg.bloom) {
      this.bloom = new UnrealBloomPass(new THREE.Vector2(w / 2, h / 2), 0.55, 0.6, 0.92);
      this.composer.addPass(this.bloom);
    }
    this.composer.addPass(new OutputPass());
    this.grade = new ShaderPass(GradeShader);
    this.composer.addPass(this.grade);
    this.resize();
  }

  resize() {
    const w = window.innerWidth, h = window.innerHeight;
    this.renderer.setSize(w, h);
    this.composer?.setSize(w, h);
    if (this.grade) this.grade.uniforms.uRes.value.set(w, h);
  }

  render(dt: number) { this.composer.render(dt); }
}
