import { describe, it, expect } from "vitest";
import Ajv2020 from "ajv/dist/2020";
import addFormats from "ajv-formats";
import fs from "node:fs";
import path from "node:path";
import { parseTask, parseComment, parseLabel } from "./task";

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

const labelSchemaPath = path.resolve(
  __dirname,
  "../../../contracts/schemas/label.schema.json",
);
const labelSchemaContent = JSON.parse(
  fs.readFileSync(labelSchemaPath, "utf-8"),
);
const validateLabel = ajv.compile(labelSchemaContent);

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
      expect(validFiles.length).toBeGreaterThanOrEqual(8);
    });

    for (const file of validFiles) {
      const isComment = file.startsWith("comment");
      const isLabel = file.startsWith("label.");
      const validate = isLabel
        ? validateLabel
        : isComment
          ? validateComment
          : validateTask;
      const parse = isLabel ? parseLabel : isComment ? parseComment : parseTask;

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
        if ("name" in parsed && "name" in raw) {
          expect(parsed.name).toBe(raw.name);
        }
      });
    }
  });

  describe("Invalid Boundary Fixtures", () => {
    it("has comprehensive invalid boundary fixtures", () => {
      expect(invalidFiles.length).toBeGreaterThanOrEqual(12);
    });

    for (const file of invalidFiles) {
      const isComment = file.includes("comment");
      const isLabel = file.includes("label") && !file.includes("labels");
      const validate = isLabel
        ? validateLabel
        : isComment
          ? validateComment
          : validateTask;
      const parse = isLabel ? parseLabel : isComment ? parseComment : parseTask;

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

  describe("Client Compatibility Suite", () => {
    const compatDir = path.resolve(
      __dirname,
      "../../../contracts/compatibility/v1-preceding-client",
    );
    const compatFiles = fs.existsSync(compatDir)
      ? fs.readdirSync(compatDir).filter((f) => f.endsWith(".json"))
      : [];

    it("has preceding client compatibility fixtures", () => {
      expect(compatFiles.length).toBeGreaterThanOrEqual(3);
    });

    for (const file of compatFiles) {
      const isComment = file.startsWith("comment");
      const isLabel = file.startsWith("label");
      const validate = isLabel
        ? validateLabel
        : isComment
          ? validateComment
          : validateTask;
      const parse = isLabel ? parseLabel : isComment ? parseComment : parseTask;

      it(`verifies backward compatibility for preceding client fixture ${file}`, () => {
        const filePath = path.join(compatDir, file);
        const raw = JSON.parse(fs.readFileSync(filePath, "utf-8"));
        const valid = validate(raw);
        expect(valid).toBe(true);
        const parsed = parse(raw);
        expect(parsed.id).toBe(raw.id);
      });
    }
  });
});
