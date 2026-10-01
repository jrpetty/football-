// Renders small head-and-shoulders portraits of survivors to data URLs,
// using a tiny offscreen renderer with its own studio lighting.
import * as THREE from 'three'
import { makeHuman, setWeapon } from './models.js'
import { lookFor } from './agents.js'

let r = null
let scene = null
let cam = null
const cache = new Map()

function init() {
  const canvas = document.createElement('canvas')
  r = new THREE.WebGLRenderer({ canvas, antialias: true, alpha: true, preserveDrawingBuffer: true })
  r.setSize(128, 128, false)
  r.setPixelRatio(1)
  r.outputColorSpace = THREE.SRGBColorSpace
  r.toneMapping = THREE.ACESFilmicToneMapping
  scene = new THREE.Scene()
  scene.add(new THREE.HemisphereLight('#dfe6ea', '#4a4036', 1.4))
  const key = new THREE.DirectionalLight('#fff0dc', 2.4)
  key.position.set(1.5, 2.5, 3)
  scene.add(key)
  const rim = new THREE.DirectionalLight('#8ab0ff', 1.2)
  rim.position.set(-2, 2, -2)
  scene.add(rim)
  cam = new THREE.PerspectiveCamera(24, 1, 0.1, 20)
  cam.position.set(0.55, 1.75, 2.1)
  cam.lookAt(0, 1.42, 0)
}

export function portrait(data) {
  const key = data.id + JSON.stringify(data.look) + (data.equip?.armor || '') + (data.equip?.gear || '')
  if (cache.has(key)) return cache.get(key)
  try {
    if (!r) init()
    const rig = makeHuman(data.equip ? lookFor(data) : data.look)
    setWeapon(rig, 'none')
    rig.root.rotation.y = 0.35
    rig.armL.rotation.x = 0.1
    rig.armR.rotation.x = -0.1
    scene.add(rig.root)
    r.render(scene, cam)
    const url = r.domElement.toDataURL('image/png')
    scene.remove(rig.root)
    cache.set(key, url)
    return url
  } catch {
    return ''
  }
}
