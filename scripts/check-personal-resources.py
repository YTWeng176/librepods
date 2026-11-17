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

# A localized main resource wins over a flavor's unqualified default. Ensure
# parallel installs remain distinguishable in every locale that names the app.
flavor = root.parents[1] / 'foss/res'
for values in root.glob('values*'):
    if not any(node.get('name') == 'app_name' for xml in values.glob('*.xml') for node in ET.parse(xml).getroot()):
        continue
    names = [node.text for xml in (flavor / values.name).glob('*.xml') for node in ET.parse(xml).getroot() if node.get('name') == 'app_name']
    if names != ['LibrePods Personal']:
        raise SystemExit(f'Personal app name missing or ambiguous: {values.name}')
print('资源 XML、重复键及中文格式占位符校验通过。')
