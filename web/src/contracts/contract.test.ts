import { describe, it, expect } from "vitest";
import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";
import fs from "node:fs";
import path from "node:path";
import { parseTask, parseComment } from "./task";

const ajv = new Ajv2020({ allErrors: true, strict: false });
addFormats(ajv);

const taskSchemaPath = path.resolve(
  __dirname,
  "../../../contracts/schemas/task.schema.json",
);
const taskSchemaContent = JSON.parse(fs.readFileSync(taskSchemaPath, "utf-8"));
const validateTask = ajv.compile(taskSchemaContent);

const commentSchemaPath = path.resolve(
  __dirname,
  "../../../contracts/schemas/comment.schema.json",
);
const commentSchemaContent = JSON.parse(
  fs.readFileSync(commentSchemaPath, "utf-8"),
);
const validateComment = ajv.compile(commentSchemaContent);

const examplesDir = path.resolve(__dirname, "../../../contracts/examples");
const validFiles = fs
  .readdirSync(examplesDir)
  .filter((f) => f.endsWith(".json"));

const invalidExamplesDir = path.resolve(
  __dirname,
  "../../../contracts/examples/invalid",
);
const invalidFiles = fs
  .readdirSync(invalidExamplesDir)
  .filter((f) => f.endsWith(".json"));

describe("Shared Contract Suite - Web", () => {
  describe("Valid Golden Fixtures", () => {
    it("has comprehensive valid golden fixtures", () => {
      expect(validFiles.length).toBeGreaterThanOrEqual(7);
    });

    for (const file of validFiles) {
      const isComment = file.startsWith("comment");
      const validate = isComment ? validateComment : validateTask;
      const parse = isComment ? parseComment : parseTask;

      it(`validates golden fixture ${file} against JSON Schema`, () => {
        const filePath = path.join(examplesDir, file);
        const data = JSON.parse(fs.readFileSync(filePath, "utf-8"));
        const valid = validate(data);
        if (!valid) {
          console.error(`Validation errors for ${file}:`, validate.errors);
        }
        expect(valid).toBe(true);
      });

      it(`deserializes golden fixture ${file} into Effect Schema model`, () => {
        const filePath = path.join(examplesDir, file);
        const raw = JSON.parse(fs.readFileSync(filePath, "utf-8"));
        const parsed = parse(raw);
        expect(parsed.id).toBe(raw.id);
        if ("title" in parsed && "title" in raw) {
          expect(parsed.title).toBe(raw.title);
        }
        if ("content" in parsed && "content" in raw) {
          expect(parsed.content).toBe(raw.content);
        }
      });
    }
  });

  describe("Invalid Boundary Fixtures", () => {
    it("has comprehensive invalid boundary fixtures", () => {
      expect(invalidFiles.length).toBeGreaterThanOrEqual(10);
    });

    for (const file of invalidFiles) {
      const isComment = file.includes("comment");
      const validate = isComment ? validateComment : validateTask;
      const parse = isComment ? parseComment : parseTask;

      it(`rejects invalid fixture ${file} with JSON Schema`, () => {
        const filePath = path.join(invalidExamplesDir, file);
        const raw = JSON.parse(fs.readFileSync(filePath, "utf-8"));
        const valid = validate(raw);
        expect(valid).toBe(false);
      });

      it(`rejects invalid fixture ${file} with Effect Schema model`, () => {
        const filePath = path.join(invalidExamplesDir, file);
        const raw = JSON.parse(fs.readFileSync(filePath, "utf-8"));
        expect(() => parse(raw)).toThrow();
      });
    }
  });
});
