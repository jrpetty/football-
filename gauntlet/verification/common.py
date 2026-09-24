import json, os, math

import os
ROOT = os.path.join(os.path.dirname(os.path.abspath(__file__)), '..', 'tests')
ONE = "Give exactly one answer. If you give more than one answer, it will be marked wrong."


def tok(s):
    """Conservative token estimate for English prompt text (~3.6 chars/token)."""
    return int(math.ceil(len(s) / 3.6)) + 20


def write_test(meta, cases, fname=None):
    cat = meta['category']
    slug = meta['id'].split('.', 1)[1]
    assert meta['id'].startswith(cat + '.')
    d = os.path.join(ROOT, cat)
    os.makedirs(d, exist_ok=True)
    obj = {
        'kind': 'prompt',
        'id': meta['id'],
        'version': meta.get('version', '1.0.0'),
        'name': meta['name'],
        'category': cat,
        'description': meta['description'],
        'difficulty': meta['difficulty'],
        'tags': meta.get('tags', []),
        'hook': meta['hook'],
        'maxOutputTokens': meta.get('maxOutputTokens', 16000),
        'estimate': meta['estimate'],
        'author': 'Gauntlet Core',
        'createdAt': '2026-09-24',
    }
    for k in ('timeLimitSec', 'system', 'preamble'):
        if k in meta:
            obj[k] = meta[k]
    obj['scorer'] = meta['scorer']
    obj['cases'] = cases
    path = os.path.join(d, (fname or slug) + '.json')
    # Large payloads (hidden code tests) are serialised compactly to keep files small.
    placeholders = {}
    for i, c in enumerate(obj['cases']):
        if 'expected' in c:
            compact = json.dumps(c['expected'], ensure_ascii=False, separators=(',', ':'))
            if len(compact) > 4000:
                key = f'@@COMPACT_{i}@@'
                placeholders[key] = compact
                c['expected'] = key
    text = json.dumps(obj, indent=2, ensure_ascii=False)
    for key, compact in placeholders.items():
        text = text.replace(json.dumps(key), compact, 1)
    json.loads(text)  # sanity
    with open(path, 'w', encoding='utf-8') as f:
        f.write(text + '\n')
    return path
