"""Generate synthetic OOXML fixtures without copying company artwork or contacts."""

import base64
import json
from pathlib import Path
from xml.sax.saxutils import escape
from zipfile import ZipFile, ZIP_DEFLATED


ROOT = Path(__file__).resolve().parents[1]
MAIN = 'http://schemas.openxmlformats.org/spreadsheetml/2006/main'
REL = 'http://schemas.openxmlformats.org/package/2006/relationships'
OFFICE = 'http://schemas.openxmlformats.org/officeDocument/2006/relationships'
DRAW = 'http://schemas.openxmlformats.org/drawingml/2006'


def generate(template):
	"""Build a deliberately plain test workbook with one synthetic pixel image."""
	path = ROOT / template['workbookPath']
	path.parent.mkdir(parents=True, exist_ok=True)
	cells = {'A1': 'SYNTHETIC TEST COMPANY', 'G7': 'sales@example.invalid', 'G8': 'TEST PHONE / TEST FAX'}
	rows = []
	for row in range(1, 41):
		content = ''.join(
			f'<c r="{column}{row}" t="inlineStr"><is><t>{escape(cells.get(f"{column}{row}", ""))}</t></is></c>'
			for column in 'ABCDEFGH'
		)
		rows.append(f'<row r="{row}" ht="18" customHeight="1">{content}</row>')
	entries = {
		'[Content_Types].xml': '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types">'
			'<Default Extension="rels" ContentType="application/vnd.openxmlformats-package.relationships+xml"/>'
			'<Default Extension="xml" ContentType="application/xml"/><Default Extension="png" ContentType="image/png"/>'
			'<Override PartName="/xl/workbook.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.sheet.main+xml"/>'
			'<Override PartName="/xl/worksheets/sheet1.xml" ContentType="application/vnd.openxmlformats-officedocument.spreadsheetml.worksheet+xml"/>'
			'<Override PartName="/xl/drawings/drawing1.xml" ContentType="application/vnd.openxmlformats-officedocument.drawing+xml"/></Types>',
		'_rels/.rels': f'<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="{OFFICE}/officeDocument" Target="xl/workbook.xml"/></Relationships>',
		'xl/workbook.xml': f'<workbook xmlns="{MAIN}" xmlns:r="{OFFICE}"><sheets><sheet name="{escape(template["sheetName"])}" sheetId="1" r:id="rId1"/></sheets><definedNames/></workbook>',
		'xl/_rels/workbook.xml.rels': f'<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="{OFFICE}/worksheet" Target="worksheets/sheet1.xml"/></Relationships>',
		'xl/worksheets/sheet1.xml': f'<worksheet xmlns="{MAIN}" xmlns:r="{OFFICE}"><sheetData>{"".join(rows)}</sheetData><drawing r:id="rId1"/></worksheet>',
		'xl/worksheets/_rels/sheet1.xml.rels': f'<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="{OFFICE}/drawing" Target="../drawings/drawing1.xml"/></Relationships>',
		'xl/drawings/drawing1.xml': f'<xdr:wsDr xmlns:xdr="{DRAW}/spreadsheetDrawing" xmlns:a="{DRAW}/main" xmlns:r="{OFFICE}">'
			'<xdr:twoCellAnchor><xdr:from><xdr:col>0</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>1</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:from>'
			'<xdr:to><xdr:col>1</xdr:col><xdr:colOff>0</xdr:colOff><xdr:row>2</xdr:row><xdr:rowOff>0</xdr:rowOff></xdr:to>'
			'<xdr:pic><xdr:nvPicPr><xdr:cNvPr id="1" name="Synthetic pixel"/><xdr:cNvPicPr/></xdr:nvPicPr>'
			'<xdr:blipFill><a:blip r:embed="rId1"/><a:stretch><a:fillRect/></a:stretch></xdr:blipFill>'
			'<xdr:spPr><a:prstGeom prst="rect"><a:avLst/></a:prstGeom></xdr:spPr></xdr:pic><xdr:clientData/></xdr:twoCellAnchor></xdr:wsDr>',
		'xl/drawings/_rels/drawing1.xml.rels': f'<Relationships xmlns="{REL}"><Relationship Id="rId1" Type="{OFFICE}/image" Target="../media/synthetic.png"/></Relationships>',
	}
	with ZipFile(path, 'w', ZIP_DEFLATED) as archive:
		for name, value in entries.items():
			archive.writestr(name, value)
		archive.writestr('xl/media/synthetic.png', base64.b64decode('iVBORw0KGgoAAAANSUhEUgAAAAEAAAABCAQAAAC1HAwCAAAAC0lEQVR42mP8/x8AAwMCAO+aLPsAAAAASUVORK5CYII='))


if __name__ == '__main__':
	definitions = json.loads((ROOT / 'src/test/resources/quotation/template-definitions.json').read_text(encoding='utf-8'))
	for definition in definitions['templates']:
		generate(definition)
	print('Generated five synthetic test workbooks; no company files were read.')
