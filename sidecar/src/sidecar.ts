// sidecar/src/sidecar.ts
import * as readline from 'readline';

interface JsonRpcRequest {
  jsonrpc: '2.0';
  method: string;
  params: Record<string, unknown>;
  id: number | null;
}

interface JsonRpcResponse {
  jsonrpc: '2.0';
  result?: unknown;
  error?: { code: number; message: string };
  id: number | null;
}

const rl = readline.createInterface({ input: process.stdin });

function respond(id: number | null, result?: unknown, error?: { code: number; message: string }) {
  const resp: JsonRpcResponse = { jsonrpc: '2.0', id };
  if (error) resp.error = error;
  else resp.result = result ?? {};
  process.stdout.write(JSON.stringify(resp) + '\n');
}

// 方法路由表
const handlers: Record<string, (params: Record<string, unknown>) => unknown> = {
  ping: (_params) => ({ pong: true, ts: Date.now() }),
  // dualContouring: (params) => { ... },
  // nurbsFitting: (params)   => { ... },
};

rl.on('line', (line: string) => {
  let req: JsonRpcRequest;
  try {
    req = JSON.parse(line);
  } catch {
    return; // 忽略非 JSON 行
  }

  if (req.method === 'shutdown') {
    process.exit(0);
  }

  const handler = handlers[req.method];
  if (!handler) {
    respond(req.id, undefined, { code: -32601, message: `Method not found: ${req.method}` });
    return;
  }

  try {
    const result = handler(req.params ?? {});
    respond(req.id, result);
  } catch (e: unknown) {
    const msg = e instanceof Error ? e.message : String(e);
    respond(req.id, undefined, { code: -32603, message: msg });
  }
});
