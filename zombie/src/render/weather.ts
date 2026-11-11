// Dynamic weather: clear → overcast → rain → storm → fog, with GPU rain streaks, lightning and wetness.
import * as THREE from 'three';
import { RNG, clamp, damp } from '../core/util';

export type WeatherKind = 'clear' | 'cloudy' | 'overcast' | 'rain' | 'storm' | 'fog';
const PRESET: Record<WeatherKind, { cover: number; overcast: number; rain: number; fog: number; wind: number; temp: number }> = {
  clear: { cover: 0.25, overcast: 0, rain: 0, fog: 0, wind: 0.2, temp: 4 },
  cloudy: { cover: 0.55, overcast: 0.3, rain: 0, fog: 0.05, wind: 0.35, temp: 1 },
  overcast: { cover: 0.85, overcast: 0.7, rain: 0, fog: 0.15, wind: 0.4, temp: 0 },
  rain: { cover: 0.95, overcast: 0.9, rain: 0.65, fog: 0.25, wind: 0.55, temp: -3 },
  storm: { cover: 1, overcast: 1, rain: 1, fog: 0.3, wind: 1, temp: -5 },
  fog: { cover: 0.7, overcast: 0.6, rain: 0, fog: 1, wind: 0.05, temp: -1 },
};

export class Weather {
  kind: WeatherKind = 'cloudy';
  cover = 0.5; overcast = 0.3; rain = 0; fog = 0; wind = 0.3; tempOffset = 0;
  wetness = 0;              // ground wetness 0..1 (dries slowly)
  lightning = 0;
  private next = 0;
  private rng: RNG;
  rainMesh: THREE.Mesh;
  private boltTimer = 10;
  onThunder: (delay: number, intensity: number) => void = () => {};
  windDir = new THREE.Vector2(1, 0.3).normalize();

  constructor(scene: THREE.Scene, seed: number) {
    this.rng = new RNG(seed);
    // rain streaks: instanced quads wrapped in a box around the camera
    const COUNT = 9000;
    const base = new THREE.PlaneGeometry(0.012, 0.55);
    const g = new THREE.InstancedBufferGeometry();
    g.index = base.index; g.attributes.position = base.attributes.position; g.attributes.uv = base.attributes.uv;
    const offs = new Float32Array(COUNT * 4);
    for (let i = 0; i < COUNT; i++) { offs[i * 4] = Math.random(); offs[i * 4 + 1] = Math.random(); offs[i * 4 + 2] = Math.random(); offs[i * 4 + 3] = Math.random(); }
    g.setAttribute('aOff', new THREE.InstancedBufferAttribute(offs, 4));
    g.instanceCount = COUNT;
    const mat = new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, fog: false,
      uniforms: { uTime: { value: 0 }, uCam: { value: new THREE.Vector3() }, uAmount: { value: 0 }, uWind: { value: new THREE.Vector2() }, uLight: { value: 1 } },
      vertexShader: `attribute vec4 aOff; uniform float uTime; uniform vec3 uCam; uniform float uAmount; uniform vec2 uWind; varying float vA; varying vec2 vUv;
        void main(){
          vUv = uv;
          float box = 36.0;
          vec3 p;
          p.x = mod(aOff.x * box - uCam.x + uWind.x * uTime * 2.0, box) - box*0.5;
          p.z = mod(aOff.z * box - uCam.z + uWind.y * uTime * 2.0, box) - box*0.5;
          p.y = mod(aOff.y * 24.0 - uTime * (16.0 + aOff.w * 6.0), 24.0) - 8.0;
          vec3 wp = uCam + p;
          // billboard around Y, tilt with wind
          vec3 camR = normalize(vec3(viewMatrix[0][0], viewMatrix[1][0], viewMatrix[2][0]));
          vec3 v = wp + camR * position.x + vec3(uWind.x*0.08, 1.0, uWind.y*0.08) * position.y;
          vA = step(aOff.w, uAmount) * (1.0 - smoothstep(10.0, 18.0, length(p.xz)));
          gl_Position = projectionMatrix * viewMatrix * vec4(v, 1.0);
        }`,
      fragmentShader: `varying float vA; varying vec2 vUv; uniform float uLight; void main(){ float a = vA * smoothstep(0.0, 0.3, vUv.y) * smoothstep(1.0, 0.6, vUv.y) * 0.35; gl_FragColor = vec4(vec3(0.75,0.8,0.88)*uLight, a); }`,
    });
    this.rainMesh = new THREE.Mesh(g, mat);
    this.rainMesh.frustumCulled = false;
    scene.add(this.rainMesh);
    this.next = 60 * 6;
  }

  set(kind: WeatherKind) { this.kind = kind; }

  /** dtGame in game minutes */
  update(dt: number, dtGameMin: number, cam: THREE.Vector3, time: number, daylight: number, indoor: boolean) {
    this.next -= dtGameMin;
    if (this.next <= 0) {
      const order: [WeatherKind, number][] = [['clear', 3], ['cloudy', 4], ['overcast', 3], ['rain', 2.5], ['storm', 0.8], ['fog', 1.2]];
      this.kind = this.rng.weighted(order);
      this.next = this.rng.range(60 * 3, 60 * 9);
    }
    const p = PRESET[this.kind];
    const k = 0.05;
    this.cover = damp(this.cover, p.cover, k, dt);
    this.overcast = damp(this.overcast, p.overcast, k, dt);
    this.rain = damp(this.rain, p.rain, k * 1.5, dt);
    this.fog = damp(this.fog, p.fog, k, dt);
    this.wind = damp(this.wind, p.wind + Math.sin(time * 0.13) * 0.1, 0.3, dt);
    this.tempOffset = damp(this.tempOffset, p.temp, k, dt);
    this.wetness = clamp(this.wetness + (this.rain > 0.1 ? this.rain * 0.02 : -0.0025) * dtGameMin, 0, 1);
    this.windDir.rotateAround(new THREE.Vector2(), Math.sin(time * 0.01) * 0.0005);

    // lightning
    this.lightning = Math.max(0, this.lightning - dt * 6);
    if (this.kind === 'storm' && this.rain > 0.6) {
      this.boltTimer -= dt;
      if (this.boltTimer <= 0) {
        this.boltTimer = this.rng.range(6, 22);
        const dist = this.rng.range(200, 2000);
        this.lightning = clamp(1.4 - dist / 2000, 0.25, 1.2);
        this.onThunder(dist / 343, this.lightning);
      }
    }
    const m = this.rainMesh.material as THREE.ShaderMaterial;
    m.uniforms.uTime.value = time; m.uniforms.uCam.value.copy(cam); m.uniforms.uAmount.value = this.rain;
    m.uniforms.uWind.value.set(this.windDir.x * this.wind * 3, this.windDir.y * this.wind * 3);
    m.uniforms.uLight.value = 0.25 + daylight * 0.75 + this.lightning;
    this.rainMesh.visible = this.rain > 0.02 && !indoor;
  }
  temperature(hours: number) {
    // base celsius: autumn in the Pacific Northwest
    const diurnal = Math.sin((hours - 9) / 24 * Math.PI * 2) * 5;
    return 9 + diurnal + this.tempOffset;
  }
}
