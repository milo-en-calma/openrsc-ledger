/** The largest id the server reads: player and item ids are 32-bit whole numbers. */
const LARGEST_ID = 2_147_483_647;

/**
 * The player or item id written in an address, or undefined unless [text] is plain digits ("0",
 * "12") the server can read. A sign, space, dot, exponent, prefix such as "0x" or leading zero is
 * refused, so a typing mistake never quietly opens another record.
 */
export function addressId(text: string): number | undefined {
  if (!/^(0|[1-9][0-9]*)$/.test(text)) {
    return undefined;
  }
  const id = Number(text);
  return id <= LARGEST_ID ? id : undefined;
}
