// ESM loader for the standalone libyuv WebAssembly module published as
// @enaium/libyuv-kmp. The module is the same one embedded in the Kotlin/JS and
// Kotlin/Wasm artifacts; this file is for plain JavaScript consumers.

const defaultWasmUrl = new URL("./libyuv_wasm.wasm", import.meta.url);

/**
 * Instantiates a libyuv WebAssembly module.
 *
 * @param {ArrayBuffer|ArrayBufferView|URL|string|Response} [source]
 * @returns {Promise<WebAssembly.Exports>}
 */
export async function initLibYuvKmp(source = defaultWasmUrl) {
  if (source instanceof ArrayBuffer) {
    return instantiateLibYuvKmp(source);
  }
  if (ArrayBuffer.isView(source)) {
    const bytes = source.buffer.slice(
      source.byteOffset,
      source.byteOffset + source.byteLength,
    );
    return instantiateLibYuvKmp(bytes);
  }
  if (source && typeof source.arrayBuffer === "function") {
    return instantiateLibYuvKmp(await source.arrayBuffer());
  }
  if (typeof source === "string" || source instanceof URL) {
    const response = await fetch(source);
    if (!response.ok) {
      throw new Error(`failed to fetch ${source}: ${response.status} ${response.statusText}`);
    }
    return instantiateLibYuvKmp(await response.arrayBuffer());
  }
  throw new TypeError("initLibYuvKmp expects a URL, Response, ArrayBuffer or typed array");
}

/**
 * Synchronously instantiates the module from its bytes. The Emscripten module
 * has no runtime imports except the no-op memory-growth notification.
 *
 * @param {ArrayBuffer|ArrayBufferView} bytes
 * @returns {WebAssembly.Exports}
 */
export function instantiateLibYuvKmp(bytes) {
  const module = new WebAssembly.Module(bytes);
  const instance = new WebAssembly.Instance(module, {
    env: { emscripten_notify_memory_growth() {} },
  });
  const exports = instance.exports;
  if (typeof exports._initialize === "function") exports._initialize();
  return exports;
}

export default initLibYuvKmp;
