import assert from "node:assert/strict";
import { test } from "node:test";
import { addressId } from "../src/pages/addressId.ts";

test("plain digits are read as the id", () => {
  assert.equal(addressId("0"), 0);
  assert.equal(addressId("12"), 12);
  assert.equal(addressId("2147483647"), 2147483647);
});

test("numbers written any other way are refused, not read as another id", () => {
  for (const text of [" ", "", "1e3", "0x10", "0b101", "0o17", "012", "00", "12.0", "12.", "+12", " 12", "12 ", "-0", "١٢"]) {
    assert.equal(addressId(text), undefined, JSON.stringify(text));
  }
});

test("numbers no id can be are refused", () => {
  for (const text of ["-5", "2147483648", "9007199254740993", "99999999999999999999999"]) {
    assert.equal(addressId(text), undefined, text);
  }
});

test("text that isn't a number is refused", () => {
  for (const text of ["abc", "12abc", "Infinity", "NaN"]) {
    assert.equal(addressId(text), undefined, text);
  }
});
