import * as THREE from 'three'
import { Pipeline } from '../render/pipeline.js'
import { Builder } from '../models/kit.js'
import { mat } from '../render/materials.js'
const q = new URLSearchParams(location.search)
const canvas = document.getElementById('c')
const pipe = new Pipeline(canvas)
pipe.setQuality('low')
pipe.resize(800, 400)
const scene = new THREE.Scene()
scene.background = new THREE.Color('#203040')
const cam = new THREE.PerspectiveCamera(40, 2, 0.1, 100)
cam.position.set(0, 8, 8)
cam.lookAt(0, 0, 0)
const sun = new THREE.DirectionalLight('#ffffff', parseFloat(q.get('i') || '1'))
sun.position.set(parseFloat(q.get('lx') || '3'), 8, parseFloat(q.get('lz') || '-5'))
scene.add(sun, sun.target)
if (q.get('shadow')) { sun.castShadow = true; pipe.renderer.shadowMap.enabled = true }
const b = new Builder()
b.box(10, 0.1, 10, { mat: 'plain', color: '#808080', y: -0.05, ao: 0 })
b.box(1, 1, 1, { mat: 'plain', color: '#c04040', y: 0.5 })
const g = b.build()
scene.add(g)
const m2 = new THREE.Mesh(new THREE.BoxGeometry(1, 1, 1), new THREE.MeshStandardMaterial({ color: '#40c040' }))
m2.position.set(2, 0.5, 0)
scene.add(m2)
if (q.get('raw')) pipe.renderer.render(scene, cam)
else pipe.render(scene, cam, 0.016)
window.__ready = 1
