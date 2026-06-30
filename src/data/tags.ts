import type { TagCategory } from '../types'

export interface TagMeta {
  category: TagCategory
  color: string
  icon: string
}

export const TAG_CATEGORIES: TagMeta[] = [
  { category: 'Goal', color: '#22c55e', icon: '⚽' },
  { category: 'Chance', color: '#84cc16', icon: '🎯' },
  { category: 'Assist', color: '#14b8a6', icon: '🅰️' },
  { category: 'Key Pass', color: '#06b6d4', icon: '🔑' },
  { category: 'Build-up', color: '#3b82f6', icon: '🧱' },
  { category: 'Press', color: '#8b5cf6', icon: '🏃' },
  { category: 'Turnover', color: '#f97316', icon: '🔄' },
  { category: 'Defensive Error', color: '#ef4444', icon: '⚠️' },
  { category: 'Set Piece', color: '#eab308', icon: '🚩' },
  { category: 'Transition', color: '#d946ef', icon: '⚡' },
  { category: 'Duel', color: '#f43f5e', icon: '🤼' },
  { category: 'Save', color: '#0ea5e9', icon: '🧤' },
  { category: 'Skill', color: '#ec4899', icon: '✨' },
  { category: 'Other', color: '#94a3b8', icon: '📌' },
]

const byCat = new Map(TAG_CATEGORIES.map((t) => [t.category, t]))

export function tagMeta(category: TagCategory): TagMeta {
  return byCat.get(category) ?? TAG_CATEGORIES[TAG_CATEGORIES.length - 1]
}

export function tagColor(category: TagCategory): string {
  return tagMeta(category).color
}
