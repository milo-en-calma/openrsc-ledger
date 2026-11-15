import createClient from "openapi-fetch";
import type { paths } from "../../generated/openapi";

/**
 * The ledger's API, typed from the committed OpenAPI spec: a route or field renamed in
 * the Kotlin code fails this app's build instead of breaking a page.
 */
export const api = createClient<paths>();
