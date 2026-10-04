// The first-person arms and weapon live in a little scene of their own,
// drawn over the finished world (after ambient occlusion, before bloom, tone
// mapping and grading, so they are graded with everything else) with the
// depth cleared: a gun never sinks into a wall, and it keeps its own field
// of view. Its lights copy the world's each frame, turned into view space,
// so it is lit by the same sun and sky as the street around it.
import * as THREE from 'three'

const _q = new THREE.Quaternion()
const _v = new THREE.Vector3()
const UP = new THREE.Vector3(0, 1, 0)

export class VMLayer {
  constructor() {
    this.scene = new THREE.Scene()
    this.camera = new THREE.PerspectiveCamera(54, 1, 0.01, 10)
    this.visible = false
    this.hemi = new THREE.HemisphereLight('#dce4e8', '#5e5444', 0.9)
    this.sun = new THREE.DirectionalLight('#fff6e8', 3)
    this.sun.target.position.set(0, 0, 0)
    // a flashlight (off by day) and the muzzle flash light; both always in
    // the scene, so switching them never recompiles a shader
    this.torch = new THREE.SpotLight('#fff1d8', 0, 6, 0.6, 0.5, 1.2)
    this.torch.position.set(0.12, -0.14, 0.16)
    this.torch.target.position.set(0, -0.1, -2)
    this.flash = new THREE.PointLight('#ffb060', 0, 2.5, 1.5)
    this.flash.position.set(0.1, -0.08, -0.6)
    this.scene.add(this.hemi, this.sun, this.sun.target, this.torch, this.torch.target, this.flash)
    this.root = new THREE.Group()
    this.scene.add(this.root)
    this.shade = 1
  }
  setAspect(a) {
    if (this.camera.aspect === a) return
    this.camera.aspect = a
    this.camera.updateProjectionMatrix()
  }
  // Copy the world's light into view space. shade: 0..1, how much of the
  // sun reaches the hands (indoors, in a building's shadow).
  sync(worldScene, atmo, camera, shade = 1) {
    this.shade += (shade - this.shade) * 0.12
    const inv = _q.copy(camera.quaternion).invert()
    if (atmo) {
      this.hemi.color.copy(atmo.hemi.color)
      this.hemi.groundColor.copy(atmo.hemi.groundColor)
      this.hemi.intensity = atmo.hemi.intensity * 1.6 * (0.55 + this.shade * 0.45)
      this.hemi.position.copy(UP).applyQuaternion(inv)
      this.sun.color.copy(atmo.sun.color)
      this.sun.intensity = atmo.sun.intensity * this.shade
      _v.copy(atmo.sun.position).sub(atmo.sun.target.position).normalize().applyQuaternion(inv)
      this.sun.position.copy(_v).multiplyScalar(5)
    }
    this.scene.environment = worldScene.environment
    // the sky's environment map is bright at the horizon, and a gun and
    // forearms are seen edge-on, where that reflection is strongest: they
    // take a third of it (still all its colour), the sun and sky do the rest
    this.scene.environmentIntensity = (worldScene.environmentIntensity ?? 1) * 0.32 * (0.4 + this.shade * 0.6)
  }
}
