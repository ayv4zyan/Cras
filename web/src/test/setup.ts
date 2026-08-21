import "@testing-library/jest-dom";

if (typeof globalThis.WebSocket === "undefined") {
  class MockWebSocket {
    static CONNECTING = 0;
    static OPEN = 1;
    static CLOSING = 2;
    static CLOSED = 3;
    readyState = MockWebSocket.OPEN;
    send() {}
    close() {}
    addEventListener() {}
    removeEventListener() {}
  }
  globalThis.WebSocket = MockWebSocket as unknown as typeof WebSocket;
}

if (typeof window !== "undefined" && typeof window.navigator !== "undefined") {
  Object.defineProperty(window.navigator, "onLine", {
    value: true,
    writable: true,
    configurable: true,
  });
}
