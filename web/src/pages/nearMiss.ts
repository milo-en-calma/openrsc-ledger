/** A page the address was probably meant to be: where it is, and what the link says. */
export interface NearMiss {
  to: string;
  label: string;
}

/**
 * The page [path] was probably meant to be, for a few addresses that are easy to type by
 * mistake: a page's name without its "s", or `/players` on its own. Undefined for any other
 * address. Only a suggestion to click; nothing goes there on its own.
 */
export function nearMiss(path: string): NearMiss | undefined {
  const parts = path.split("/").filter((part) => part !== "");
  const [first, second, ...rest] = parts;
  if (rest.length > 0) {
    return undefined;
  }
  if (first === "players" && second === undefined) {
    return { to: "/", label: "Players" };
  }
  if (first === "player" && second !== undefined) {
    return address(`/players/${second}`);
  }
  if (first === "leaderboard") {
    return address(second === undefined ? "/leaderboards" : `/leaderboards/${second}`);
  }
  if (first === "item" && second !== undefined) {
    return address(`/items/${second}`);
  }
  return undefined;
}

function address(to: string): NearMiss {
  return { to, label: to };
}
