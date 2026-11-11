// Ambient life around the camera, fully GPU-animated: tumbling autumn leaves, fireflies on warm nights, sunlit dust motes.
import * as THREE from 'three';

export class AmbientParticles {
  leaves: THREE.Mesh; fireflies: THREE.Points; dust: THREE.Points;
  constructor(scene: THREE.Scene) {
    // leaves: instanced quads tumbling down in a wrap-around box
    const N = 260;
    const base = new THREE.PlaneGeometry(0.09, 0.07);
    const g = new THREE.InstancedBufferGeometry();
    g.index = base.index; g.attributes.position = base.attributes.position;
    const off = new Float32Array(N * 4); for (let i = 0; i < N * 4; i++) off[i] = Math.random();
    g.setAttribute('aOff', new THREE.InstancedBufferAttribute(off, 4)); g.instanceCount = N;
    const leafMat = new THREE.ShaderMaterial({
      side: THREE.DoubleSide, transparent: true, depthWrite: false,
      uniforms: { uTime: { value: 0 }, uCam: { value: new THREE.Vector3() }, uAmount: { value: 0 }, uLight: { value: 1 }, uWind: { value: new THREE.Vector2(1, 0) } },
      vertexShader: `attribute vec4 aOff; uniform float uTime, uAmount; uniform vec3 uCam; uniform vec2 uWind; varying vec3 vC; varying float vA;
        mat3 rot(vec3 a){ float cx=cos(a.x),sx=sin(a.x),cy=cos(a.y),sy=sin(a.y),cz=cos(a.z),sz=sin(a.z);
          return mat3(cy*cz, cy*sz, -sy, sx*sy*cz-cx*sz, sx*sy*sz+cx*cz, sx*cy, cx*sy*cz+sx*sz, cx*sy*sz-sx*cz, cx*cy); }
        void main(){
          float box = 30.0; float t = uTime * (0.6 + aOff.w * 0.5);
          vec3 p;
          p.x = mod(aOff.x * box - uCam.x + uWind.x * uTime * 1.2 + sin(t * 1.7 + aOff.y * 9.0) * 1.2, box) - box * 0.5;
          p.z = mod(aOff.z * box - uCam.z + uWind.y * uTime * 1.2 + cos(t * 1.3 + aOff.x * 7.0) * 1.2, box) - box * 0.5;
          p.y = mod(aOff.y * 14.0 - uTime * (0.7 + aOff.w * 0.6), 14.0) - 3.0;
          vec3 local = rot(vec3(t * 2.1 + aOff.x * 6.0, t * 1.4 + aOff.z * 6.0, t * 1.1)) * position;
          vec3 wp = uCam + p + local;
          vC = mix(vec3(0.85, 0.42, 0.12), vec3(0.9, 0.72, 0.2), aOff.w); if (aOff.z > 0.8) vC = vec3(0.6, 0.18, 0.1);
          vA = step(aOff.w, uAmount) * (1.0 - smoothstep(9.0, 14.0, length(p.xz)));
          gl_Position = projectionMatrix * viewMatrix * vec4(wp, 1.0);
        }`,
      fragmentShader: `varying vec3 vC; varying float vA; uniform float uLight; void main(){ if (vA < 0.01) discard; gl_FragColor = vec4(vC * uLight, vA); }`,
    });
    this.leaves = new THREE.Mesh(g, leafMat); this.leaves.frustumCulled = false; scene.add(this.leaves);

    const mkPoints = (n: number, additive: boolean, vs: string, fs: string) => {
      const pg = new THREE.BufferGeometry(); const a = new Float32Array(n * 3); for (let i = 0; i < n * 3; i++) a[i] = Math.random();
      pg.setAttribute('position', new THREE.BufferAttribute(a, 3));
      const m = new THREE.ShaderMaterial({ transparent: true, depthWrite: false, blending: additive ? THREE.AdditiveBlending : THREE.NormalBlending, uniforms: { uTime: { value: 0 }, uCam: { value: new THREE.Vector3() }, uAmount: { value: 0 }, uLight: { value: 1 } }, vertexShader: vs, fragmentShader: fs });
      const pts = new THREE.Points(pg, m); pts.frustumCulled = false; scene.add(pts); return pts;
    };
    this.fireflies = mkPoints(160, true, `uniform float uTime, uAmount; uniform vec3 uCam; varying float vB;
      void main(){ vec3 o = position; float box = 40.0;
        vec3 p = vec3(mod(o.x * box - uCam.x + sin(uTime * 0.4 + o.y * 20.0) * 2.0, box) - box * 0.5, 0.4 + o.y * 2.5 + sin(uTime * 0.9 + o.z * 30.0) * 0.4, mod(o.z * box - uCam.z + cos(uTime * 0.35 + o.x * 20.0) * 2.0, box) - box * 0.5);
        vec4 mv = viewMatrix * vec4(vec3(uCam.x, 0.0, uCam.z) + p + vec3(0.0, uCam.y - 1.6, 0.0), 1.0);
        vB = step(o.x, uAmount) * pow(max(0.0, sin(uTime * (1.5 + o.z * 2.0) + o.x * 40.0)), 3.0) * (1.0 - smoothstep(12.0, 20.0, length(p.xz)));
        gl_PointSize = 40.0 / -mv.z; gl_Position = projectionMatrix * mv; }`,
      `varying float vB; void main(){ float d = length(gl_PointCoord - 0.5); gl_FragColor = vec4(vec3(0.75, 1.0, 0.35) * vB * smoothstep(0.5, 0.0, d), 1.0); }`);
    this.dust = mkPoints(220, true, `uniform float uTime, uAmount; uniform vec3 uCam; varying float vB;
      void main(){ vec3 o = position; float box = 16.0;
        vec3 p = vec3(mod(o.x * box - uCam.x + uTime * 0.05, box) - box * 0.5, mod(o.y * 6.0 + sin(uTime * 0.3 + o.x * 9.0) * 0.3, 6.0) - 1.5, mod(o.z * box - uCam.z, box) - box * 0.5);
        vec4 mv = viewMatrix * vec4(uCam + p, 1.0); vB = step(o.z, uAmount) * (1.0 - smoothstep(4.0, 8.0, length(p))); gl_PointSize = 6.0 / -mv.z; gl_Position = projectionMatrix * mv; }`,
      `varying float vB; uniform float uLight; void main(){ float d = length(gl_PointCoord - 0.5); gl_FragColor = vec4(vec3(1.0, 0.95, 0.8) * vB * 0.35 * uLight * smoothstep(0.5, 0.0, d), 1.0); }`);
  }
  update(time: number, cam: THREE.Vector3, o: { season: number; night: number; daylight: number; rain: number; indoor: boolean; wind: THREE.Vector2; forest: number }) {
    const lm = this.leaves.material as THREE.ShaderMaterial;
    lm.uniforms.uTime.value = time; lm.uniforms.uCam.value.copy(cam); lm.uniforms.uWind.value.copy(o.wind);
    lm.uniforms.uAmount.value = o.indoor ? 0 : (o.season === 0 ? 0.25 + o.forest * 0.6 : o.season === 3 ? 0.05 : 0) * (1 - o.rain * 0.5);
    lm.uniforms.uLight.value = 0.25 + o.daylight * 0.85;
    const fm = this.fireflies.material as THREE.ShaderMaterial;
    fm.uniforms.uTime.value = time; fm.uniforms.uCam.value.copy(cam);
    fm.uniforms.uAmount.value = o.indoor || o.rain > 0.2 ? 0 : (o.season === 2 || o.season === 3 ? 0.9 : o.season === 0 ? 0.25 : 0) * o.night;
    const dm = this.dust.material as THREE.ShaderMaterial;
    dm.uniforms.uTime.value = time; dm.uniforms.uCam.value.copy(cam);
    dm.uniforms.uAmount.value = o.rain > 0.1 ? 0 : o.indoor ? 0.8 : 0.4 * o.daylight; dm.uniforms.uLight.value = o.daylight;
  }
}
