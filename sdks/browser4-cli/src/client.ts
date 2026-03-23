import axios, { AxiosInstance } from 'axios';

export interface Browser4ClientConfig {
  baseUrl?: string;
  timeout?: number;
  sessionId?: string;
}

export interface ToolCallResult {
  success: boolean;
  content?: unknown;
  error?: string;
}

/**
 * Thin HTTP client for the Browser4 REST / MCP API.
 */
export class Browser4Client {
  private readonly http: AxiosInstance;
  public sessionId?: string;

  constructor(config: Browser4ClientConfig = {}) {
    const baseURL = (config.baseUrl ?? 'http://localhost:8182').replace(/\/$/, '');
    this.http = axios.create({
      baseURL,
      timeout: config.timeout ?? 30_000,
      headers: { 'Content-Type': 'application/json' }
    });
    this.sessionId = config.sessionId;
  }

  async createSession(): Promise<string> {
    const res = await this.http.post<{ sessionId: string }>('/api/session');
    this.sessionId = res.data.sessionId;
    return this.sessionId;
  }

  async callTool(toolName: string, args: Record<string, unknown>): Promise<ToolCallResult> {
    const sessionId = this.requireSession();
    const res = await this.http.post<ToolCallResult>('/mcp/call-tool', {
      sessionId,
      toolName,
      arguments: args
    });
    return res.data;
  }

  async closeSession(sessionId?: string): Promise<void> {
    const sid = sessionId ?? this.requireSession();
    await this.http.delete(`/api/session/${sid}`);
  }

  private requireSession(): string {
    if (!this.sessionId) {
      throw new Error('No active session. Call createSession() first.');
    }
    return this.sessionId;
  }
}
