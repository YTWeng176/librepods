#!/usr/bin/env python3
"""Validate resource XML and format placeholders in the personal Chinese translations."""
from pathlib import Path
import re
import xml.etree.ElementTree as ET

root = Path(__file__).resolve().parents[1] / 'android/app/src/main/res'
for xml in root.glob('values*/*.xml'):
    document = ET.parse(xml).getroot()
    seen = set()
    for item in document:
        key = (item.tag, item.get('name'))
        if key in seen:
            raise SystemExit(f'Duplicate resource {key}: {xml}')
        seen.add(key)

def strings(locale):
    return {node.get('name'): node for node in ET.parse(root / locale / 'strings.xml').getroot() if node.tag == 'string'}

default = strings('values')
placeholder = re.compile(r'%(?:(\d+)\$)?[\-#+ 0,(]*\d*(?:\.\d+)?([a-zA-Z])')
def formats(node):
    return sorted(placeholder.findall(''.join(node.itertext()).replace('%%', '')))

for locale in ('values-zh-rCN', 'values-zh-rTW'):
    for key, node in strings(locale).items():
        if key in default and formats(node) != formats(default[key]):
            raise SystemExit(f'Format mismatch: {locale}/{key}')
print('资源 XML、重复键及中文格式占位符校验通过。')
