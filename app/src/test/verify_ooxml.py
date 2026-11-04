#!/usr/bin/env python3
"""Validate the sample archives emitted by DocumentBuildersTest."""
from pathlib import Path
from zipfile import ZipFile
from xml.dom import minidom

ROOT = Path('/tmp/andagent-r8-samples')
REQUIRED = {
    'sample.docx': ['word/document.xml', 'word/styles.xml', 'word/numbering.xml', 'docProps/core.xml'],
    'sample.pptx': ['ppt/presentation.xml', 'ppt/slides/slide1.xml', 'ppt/slides/slide2.xml', 'ppt/slideLayouts/slideLayout1.xml', 'ppt/slideMasters/slideMaster1.xml', 'ppt/theme/theme1.xml'],
    'sample.xlsx': ['xl/workbook.xml', 'xl/styles.xml', 'xl/worksheets/sheet1.xml', 'xl/worksheets/sheet2.xml'],
}
for filename, required in REQUIRED.items():
    with ZipFile(ROOT / filename) as archive:
        assert archive.testzip() is None, filename
        names = set(archive.namelist())
        assert {'[Content_Types].xml', '_rels/.rels', *required} <= names, (filename, names)
        types = minidom.parseString(archive.read('[Content_Types].xml'))
        overrides = {node.getAttribute('PartName').lstrip('/') for node in types.getElementsByTagName('Override')}
        assert set(required) <= overrides, (filename, overrides)
        for part in names:
            if part.endswith(('.xml', '.rels')):
                minidom.parseString(archive.read(part))
        print(f'{filename}: {len(names)} parts, XML valid, content types complete')
