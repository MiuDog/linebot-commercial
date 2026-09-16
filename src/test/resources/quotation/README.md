# Synthetic regression fixtures

The five XLSX files are generated from scratch by `scripts/generate-test-templates.py`.
They contain a synthetic pixel and fictional contacts, not company artwork or seals.
The row capacities intentionally exercise the existing formula and pagination contracts.
They are test resources only and must not be used as customer-approved production templates.

The SQLite catalog uses synthetic prices (item order × 10), generic TEST specifications,
and TEST ONLY remarks. Production catalogs are provisioned through company asset imports.
