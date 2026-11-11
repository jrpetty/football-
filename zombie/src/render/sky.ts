// Atmosphere: physically-based sky, volumetric-looking cloud layer, stars, moon, sun/moon light, fog, environment lighting.
import * as THREE from 'three';
import { clamp, lerp, smoothstep } from '../core/util';
import { getCloudNoiseTexture } from './textures';

export class Atmosphere {
  sky: THREE.Mesh;
  sun: THREE.DirectionalLight;
  hemi: THREE.HemisphereLight;
  fog: THREE.FogExp2;
  sunDir = new THREE.Vector3();
  moonDir = new THREE.Vector3();
  daylight = 1;            // 0 night .. 1 day
  night = 0;
  clouds: THREE.Mesh;
  haze: THREE.Mesh;
  stars: THREE.Points;
  moon: THREE.Mesh;
  private envScene = new THREE.Scene();
  private envSky: THREE.Mesh;
  private pmrem: THREE.PMREMGenerator;
  private envRT: THREE.WebGLRenderTarget | null = null;
  private lastEnvT = -999;
  private lastEnvCloud = -1;
  cloudCover = 0.5;
  overcast = 0;            // 0..1 from weather
  fogBoost = 0;
  lightning = 0;
  indoorDim = 1;
  shadowExtent = 70;
  fogColor = new THREE.Color();

  constructor(private scene: THREE.Scene, private renderer: THREE.WebGLRenderer, shadowSize: number, shadowExtent: number, private far: number) {
    this.shadowExtent = shadowExtent;
    // stylised gradient sky (zenith -> horizon, matching the fog colour exactly) with a soft sun glow
    const skyMat = new THREE.ShaderMaterial({
      side: THREE.BackSide, depthWrite: false, fog: false,
      uniforms: { uZenith: { value: new THREE.Color() }, uHorizon: { value: new THREE.Color() }, uGround: { value: new THREE.Color() }, uSunDir: { value: new THREE.Vector3(0, 1, 0) }, uSunCol: { value: new THREE.Color() }, uGlow: { value: 0.5 }, uDisc: { value: 1 } },
      vertexShader: `varying vec3 vDir; void main(){ vDir = normalize(position); vec4 p = projectionMatrix * modelViewMatrix * vec4(position,1.0); gl_Position = p.xyww; }`,
      fragmentShader: `uniform vec3 uZenith, uHorizon, uGround, uSunCol, uSunDir; uniform float uGlow, uDisc; varying vec3 vDir;
        void main(){
          vec3 d = normalize(vDir);
          float h = d.y;
          vec3 col = h > 0.0 ? mix(uHorizon, uZenith, pow(smoothstep(0.0, 0.75, h), 0.7)) : mix(uHorizon, uGround, smoothstep(0.0, -0.2, h));
          float s = max(dot(d, normalize(uSunDir)), 0.0);
          col += uSunCol * (pow(s, 8.0) * 0.35 + pow(s, 64.0) * 0.5) * uGlow;
          col = mix(col, uSunCol * 3.0, smoothstep(0.9993, 0.9996, s) * uDisc);
          gl_FragColor = vec4(col, 1.0);
        }`,
    });
    this.sky = new THREE.Mesh(new THREE.SphereGeometry(2400, 32, 16), skyMat);
    this.sky.frustumCulled = false; this.sky.renderOrder = -3;
    scene.add(this.sky);
    this.envSky = new THREE.Mesh(new THREE.SphereGeometry(900, 32, 16), skyMat);
    this.envScene.add(this.envSky);
    this.pmrem = new THREE.PMREMGenerator(renderer);

    this.sun = new THREE.DirectionalLight(0xfff1dd, 3);
    this.sun.castShadow = true;
    this.sun.shadow.mapSize.set(shadowSize, shadowSize);
    const c = this.sun.shadow.camera;
    c.left = -shadowExtent; c.right = shadowExtent; c.top = shadowExtent; c.bottom = -shadowExtent; c.near = 1; c.far = 500;
    this.sun.shadow.bias = -0.0004; this.sun.shadow.normalBias = 0.04;
    this.sun.shadow.radius = 2;
    scene.add(this.sun); scene.add(this.sun.target);

    this.hemi = new THREE.HemisphereLight(0xbfd4ee, 0x4a4234, 0.6);
    scene.add(this.hemi);

    this.fog = new THREE.FogExp2(0x9aa6ad, 0.004);
    scene.fog = this.fog;

    // cloud layer: huge plane with fbm alpha, lit by sun colour
    const cloudMat = new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, fog: false,
      uniforms: {
        tNoise: { value: getCloudNoiseTexture() }, uTime: { value: 0 }, uCover: { value: 0.5 },
        uSunCol: { value: new THREE.Color(1, 1, 1) }, uShade: { value: new THREE.Color(0.5, 0.55, 0.6) }, uSunDir: { value: new THREE.Vector3() }, uDark: { value: 0 },
        uFlash: { value: 0 },
      },
      vertexShader: `varying vec3 vW; void main(){ vec4 w = modelMatrix * vec4(position,1.0); vW = w.xyz; gl_Position = projectionMatrix * viewMatrix * w; }`,
      fragmentShader: `uniform sampler2D tNoise; uniform float uTime, uCover, uDark, uFlash; uniform vec3 uSunCol, uShade, uSunDir; varying vec3 vW;
        void main(){
          vec2 p = vW.xz * 0.00022 + vec2(uTime*0.004, uTime*0.0016);
          float n = texture2D(tNoise, p).r * 0.6 + texture2D(tNoise, p*2.7 + 0.3).r * 0.28 + texture2D(tNoise, p*7.1 - uTime*0.002).r * 0.12;
          float d = smoothstep(1.0 - uCover + 0.02, 1.0 - uCover + 0.07, n);
          // fake self-shadowing toward sun
          float n2 = texture2D(tNoise, p + uSunDir.xz * 0.012).r * 0.6 + texture2D(tNoise, (p + uSunDir.xz*0.012)*2.7 + 0.3).r * 0.28;
          float shadow = clamp((n2 - n) * 4.0 + 0.5, 0.0, 1.0);
          vec3 col = mix(uSunCol, uShade, smoothstep(0.45, 0.55, shadow) * 0.85);
          col = mix(col, vec3(0.02,0.025,0.035), uDark) + vec3(0.6,0.65,0.8) * uFlash;
          float dist = length(vW.xz - cameraPosition.xz);
          float fade = 1.0 - smoothstep(1500.0, 2900.0, dist);
          gl_FragColor = vec4(col, d * fade * 0.94);
        }`,
    });
    this.clouds = new THREE.Mesh(new THREE.PlaneGeometry(9000, 9000).rotateX(Math.PI / 2), cloudMat);
    this.clouds.position.y = 420; this.clouds.renderOrder = -1; this.clouds.frustumCulled = false;
    scene.add(this.clouds);

    // horizon haze dome: blends the sky into the fog colour so distant terrain melts into the atmosphere
    const hazeMat = new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, fog: false, side: THREE.BackSide,
      uniforms: { uColor: { value: new THREE.Color() }, uStrength: { value: 1 }, uHeight: { value: 0.25 } },
      vertexShader: `varying vec3 vDir; void main(){ vDir = normalize(position); vec4 p = projectionMatrix * modelViewMatrix * vec4(position,1.0); gl_Position = p.xyww; }`,
      fragmentShader: `uniform vec3 uColor; uniform float uStrength; uniform float uHeight; varying vec3 vDir;
        void main(){ float y = vDir.y; float a = y < 0.0 ? 1.0 : pow(1.0 - smoothstep(0.0, uHeight, y), 1.6); gl_FragColor = vec4(uColor, a * uStrength); }`,
    });
    this.haze = new THREE.Mesh(new THREE.SphereGeometry(1800, 32, 16), hazeMat);
    this.haze.frustumCulled = false; this.haze.renderOrder = 0;
    scene.add(this.haze);

    // stars
    const sg = new THREE.BufferGeometry(); const sp: number[] = []; const ss: number[] = [];
    for (let i = 0; i < 2600; i++) {
      const v = new THREE.Vector3().randomDirection(); if (v.y < 0.02) v.y = Math.abs(v.y) + 0.02; v.normalize().multiplyScalar(2000);
      sp.push(v.x, v.y, v.z); ss.push(Math.random() ** 3 * 2.5 + 0.6);
    }
    sg.setAttribute('position', new THREE.Float32BufferAttribute(sp, 3));
    sg.setAttribute('size', new THREE.Float32BufferAttribute(ss, 1));
    const starMat = new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, blending: THREE.AdditiveBlending, fog: false,
      uniforms: { uOpacity: { value: 0 }, uTime: { value: 0 } },
      vertexShader: `attribute float size; varying float vS; uniform float uTime; void main(){ vS = size; vec4 mv = modelViewMatrix * vec4(position,1.0); gl_PointSize = size * (1.0 + 0.3*sin(uTime*3.0 + position.x)); gl_Position = projectionMatrix * mv; }`,
      fragmentShader: `uniform float uOpacity; varying float vS; void main(){ float d = length(gl_PointCoord - 0.5); float a = smoothstep(0.5, 0.0, d); gl_FragColor = vec4(vec3(0.85,0.9,1.0), a * uOpacity * min(1.0, vS*0.6)); }`,
    });
    this.stars = new THREE.Points(sg, starMat); this.stars.frustumCulled = false; this.stars.renderOrder = -2;
    scene.add(this.stars);

    // moon
    const moonMat = new THREE.ShaderMaterial({
      transparent: true, depthWrite: false, fog: false, uniforms: { uOpacity: { value: 0 } },
      vertexShader: `varying vec2 vUv; void main(){ vUv = uv; gl_Position = projectionMatrix * modelViewMatrix * vec4(position,1.0); }`,
      fragmentShader: `uniform float uOpacity; varying vec2 vUv; float h(vec2 p){return fract(sin(dot(p,vec2(41.3,289.1)))*45758.5);}
        void main(){ vec2 p = vUv*2.0-1.0; float r = length(p); if (r > 1.0) { float glow = smoothstep(2.0, 1.0, r) * 0.25; gl_FragColor = vec4(vec3(0.6,0.7,0.9), glow*uOpacity); return; }
          float crater = 0.0; for (int i=0;i<6;i++){ vec2 c = vec2(h(vec2(float(i),1.0)), h(vec2(float(i),2.0)))*1.4-0.7; crater += smoothstep(0.25, 0.0, length(p-c)) * 0.12; }
          vec3 col = vec3(0.92,0.93,0.95) * (1.0 - crater) * (0.85 + 0.15*sqrt(1.0-r*r));
          gl_FragColor = vec4(col*1.6, uOpacity); }`,
    });
    this.moon = new THREE.Mesh(new THREE.PlaneGeometry(175, 175), moonMat);
    this.moon.frustumCulled = false; this.moon.renderOrder = -1;
    scene.add(this.moon);
  }

  /** hours 0..24 */
  update(hours: number, dt: number, camera: THREE.Camera, focus: THREE.Vector3, time: number) {
    // sun path
    const dayT = (hours - 6) / 12; // 0 at sunrise, 1 at sunset
    const elev = Math.sin(dayT * Math.PI) * 0.95 - 0.04;
    const az = lerp(-Math.PI * 0.6, Math.PI * 0.6, dayT);
    this.sunDir.set(Math.sin(az) * Math.cos(elev), Math.sin(elev), -Math.cos(az) * Math.cos(elev) * 0.6 - 0.4).normalize();
    // moon roughly opposite
    const mt = ((hours + 12) % 24 - 6) / 12;
    const mel = Math.sin(mt * Math.PI) * 0.8 + 0.05;
    const maz = lerp(-Math.PI * 0.5, Math.PI * 0.5, mt);
    this.moonDir.set(Math.sin(maz) * Math.cos(mel), Math.sin(mel), Math.cos(maz) * Math.cos(mel) * 0.6 + 0.3).normalize();

    const sunUp = smoothstep(-0.1, 0.14, this.sunDir.y);
    this.daylight = sunUp;
    this.night = 1 - smoothstep(-0.42, 0.0, this.sunDir.y);
    const twilight = smoothstep(-0.42, -0.02, this.sunDir.y) * (1 - sunUp);
    const golden = smoothstep(0.35, 0.02, this.sunDir.y) * sunUp;
    const oc = this.overcast;

    this.sky.position.copy(camera.position);

    // light
    const useMoon = this.sunDir.y < 0.02;
    const ldir = useMoon ? this.moonDir : this.sunDir;
    const sunCol = new THREE.Color().setRGB(1, lerp(0.95, 0.62, golden), lerp(0.88, 0.4, golden));
    const sunI = sunUp * lerp(2.7, 0.8, oc) * lerp(1, 0.7, golden);
    const moonI = (1 - sunUp) * smoothstep(0.0, 0.25, this.moonDir.y) * lerp(0.75, 0.3, oc);
    if (useMoon) { this.sun.color.setRGB(0.55, 0.65, 0.95); this.sun.intensity = moonI + this.lightning * 4; }
    else { this.sun.color.copy(sunCol); this.sun.intensity = sunI + this.lightning * 4; }
    // shadow follows focus, snapped to texels to avoid shimmering
    const ext = this.shadowExtent; const texel = (ext * 2) / this.sun.shadow.mapSize.x;
    const fx = Math.round(focus.x / texel) * texel, fz = Math.round(focus.z / texel) * texel;
    this.sun.target.position.set(fx, focus.y, fz);
    this.sun.position.set(fx + ldir.x * 200, focus.y + ldir.y * 200, fz + ldir.z * 200);
    this.sun.target.updateMatrixWorld();

    const hemiDay = new THREE.Color(0xb5c8e0).lerp(new THREE.Color(0x9aa3ad), oc);
    const hemiNight = new THREE.Color(0x2a3a5a);
    this.hemi.color.copy(hemiNight).lerp(hemiDay, sunUp).lerp(new THREE.Color(0xd8a080), golden * 0.35);
    this.hemi.groundColor.set(0x3a3428).lerp(new THREE.Color(0x07080a), 1 - sunUp);
    this.hemi.intensity = lerp(0.16 + twilight * 0.36, lerp(0.38, 0.62, oc), sunUp) * this.indoorDim + this.lightning * 2;

    // fog colour from horizon
    const dayFog = new THREE.Color(0xa9b7c2).lerp(new THREE.Color(0x8e979c), oc);
    const sunsetFog = new THREE.Color(0xc89270);
    const nightFog = new THREE.Color(0x0b0f17);
    this.fogColor.copy(dayFog).lerp(sunsetFog, golden * (1 - oc * 0.7)).lerp(nightFog, this.night);
    this.fog.color.copy(this.fogColor);
    const baseDensity = 2.2 / this.far;
    this.fog.density = baseDensity * (1 + oc * 1.4 + this.fogBoost * 4) + this.night * 0.002;

    // gradient sky colours
    const su = (this.sky.material as THREE.ShaderMaterial).uniforms;
    const zen = new THREE.Color(0x5a82b0).lerp(new THREE.Color(0x8e99a1), oc).lerp(new THREE.Color(0x4f5f86), golden * (1 - oc * 0.6)).lerp(new THREE.Color(0x05080f), this.night);
    su.uZenith.value.copy(zen);
    su.uHorizon.value.copy(this.fogColor);
    su.uGround.value.copy(this.fogColor).multiplyScalar(0.8);
    su.uSunDir.value.copy(this.sunDir);
    su.uSunCol.value.copy(sunCol).multiplyScalar(sunUp);
    su.uGlow.value = (0.4 + golden * 1.2) * (1 - oc * 0.8);
    su.uDisc.value = (1 - smoothstep(0.5, 0.9, this.cloudCover)) * sunUp;
    this.haze.visible = false;
    // haze
    const hm = this.haze.material as THREE.ShaderMaterial;
    hm.uniforms.uColor.value.copy(this.fogColor);
    hm.uniforms.uStrength.value = Math.min(1, 0.85 + oc * 0.15 + this.night * 0.15 + this.fogBoost);
    hm.uniforms.uHeight.value = 0.32 + oc * 0.35 + this.fogBoost * 0.4;
    this.haze.position.copy(camera.position);

    // clouds
    const cm = this.clouds.material as THREE.ShaderMaterial;
    cm.uniforms.uTime.value = time;
    cm.uniforms.uCover.value = clamp(this.cloudCover, 0, 1);
    cm.uniforms.uSunCol.value.copy(sunCol).multiplyScalar(lerp(0.4, 1.1, sunUp) * lerp(1, 0.7, oc));
    cm.uniforms.uShade.value.set(0.42, 0.46, 0.52).multiplyScalar(lerp(0.15, 1, sunUp) * lerp(1, 0.75, oc));
    cm.uniforms.uSunDir.value.copy(this.sunDir);
    cm.uniforms.uDark.value = this.night * 0.92;
    cm.uniforms.uFlash.value = this.lightning;
    this.clouds.position.x = camera.position.x; this.clouds.position.z = camera.position.z;

    const starO = this.night * (1 - this.cloudCover * 0.8);
    (this.stars.material as THREE.ShaderMaterial).uniforms.uOpacity.value = starO;
    (this.stars.material as THREE.ShaderMaterial).uniforms.uTime.value = time;
    this.stars.position.copy(camera.position);
    this.stars.rotation.y = hours / 24 * Math.PI * 2 * 0.2;
    this.moon.position.copy(camera.position).addScaledVector(this.moonDir, 2000);
    this.moon.lookAt(camera.position);
    (this.moon.material as THREE.ShaderMaterial).uniforms.uOpacity.value = smoothstep(-0.05, 0.1, this.moonDir.y) * (1 - this.cloudCover * 0.6) * (0.3 + this.night * 0.7);

    // environment map refresh
    if (Math.abs(hours - this.lastEnvT) > 0.35 || Math.abs(oc - this.lastEnvCloud) > 0.1) this.refreshEnv(hours, oc);
    (this.scene as any).environmentIntensity = (lerp(0.05 + twilight * 0.2, lerp(0.42, 0.5, oc), sunUp) + this.lightning) * this.indoorDim;
    (this.scene as any).backgroundIntensity = 1;
  }

  private refreshEnv(hours: number, oc: number) {
    this.lastEnvT = hours; this.lastEnvCloud = oc;
    const old = this.envRT;
    this.envRT = this.pmrem.fromScene(this.envScene, 0, 0.1, 2000);
    this.scene.environment = this.envRT.texture;
    if (old) old.dispose();
  }

  /** tone-mapping exposure suggestion so nights read dark but not pitch black */
  exposure() { return lerp(1.15, 1.0, this.daylight) * lerp(1.25, 1, this.indoorDim) * lerp(1, 0.85, this.overcast) + this.lightning * 0.5; }
}
