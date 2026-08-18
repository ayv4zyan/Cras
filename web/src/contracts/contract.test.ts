import { describe, it, expect } from 'vitest';
import Ajv2020 from 'ajv/dist/2020';
import addFormats from 'ajv-formats';
import fs from 'node:fs';
import path from 'node:path';
import { parseTask } from './task';

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

const schemaPath = path.resolve(__dirname, '../../../contracts/schemas/task.schema.json');
const schemaContent = JSON.parse(fs.readFileSync(schemaPath, 'utf-8'));
const validate = ajv.compile(schemaContent);

const examplesDir = path.resolve(__dirname, '../../../contracts/examples');
const exampleFiles = fs.readdirSync(examplesDir).filter((f) => f.endsWith('.json'));

describe('Shared Task Contract Seam', () => {
  it('has at least 4 golden example fixtures', () => {
    expect(exampleFiles.length).toBeGreaterThanOrEqual(4);
  });

  for (const file of exampleFiles) {
    it(`validates golden fixture ${file} against JSON Schema`, () => {
      const filePath = path.join(examplesDir, file);
      const data = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
      const valid = validate(data);
      if (!valid) {
        console.error(`Validation errors for ${file}:`, validate.errors);
      }
      expect(valid).toBe(true);
    });

    it(`deserializes golden fixture ${file} into TypeScript Task model`, () => {
      const filePath = path.join(examplesDir, file);
      const raw = JSON.parse(fs.readFileSync(filePath, 'utf-8'));
      const parsed = parseTask(raw);
      expect(parsed.id).toBe(raw.id);
      expect(parsed.title).toBe(raw.title);
      expect(parsed.version).toBe(raw.version);
    });
  }

  it('rejects an invalid task fixture with missing required fields or bad plan', () => {
    const invalidTask = {
      id: 'not-a-uuid',
      title: '',
      // missing priority, labels, version, createdAt, updatedAt
    };
    const valid = validate(invalidTask);
    expect(valid).toBe(false);
    expect(() => parseTask(invalidTask)).toThrow();
  });
});
