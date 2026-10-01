// Small fixes to three.js for this game, loaded before anything else.
import * as THREE from 'three'

// Object3D.copy (and so every clone) deep-copies userData with a JSON round
// trip. Our models keep scene objects, geometry and textures in userData, so
// a clone serialized all of it, image pixels included: placing a farm took
// seconds. A shallow copy is all the game needs; every key it writes on a
// clone's userData is its own.
const copy = THREE.Object3D.prototype.copy
const NONE = {}
THREE.Object3D.prototype.copy = function (source, recursive) {
  const ud = source.userData
  source.userData = NONE
  try {
    copy.call(this, source, recursive)
  } finally {
    source.userData = ud
  }
  this.userData = { ...ud }
  return this
}
