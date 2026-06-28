import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, tap, BehaviorSubject, Subject } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private readonly GATEWAY_URL = 'http://localhost:8080';

  private agentIdSubject = new BehaviorSubject<string>('');
  agentId$ = this.agentIdSubject.asObservable();

  private loginSuccessSubject = new BehaviorSubject<boolean>(false);
  loginSuccess$ = this.loginSuccessSubject.asObservable();

  /**
   * Emits when the backend forces a logout (blacklisted token → 403 TOKEN_EXPIRED,
   * or WebSocket LogoutNotification). AppComponent subscribes to this and switches to
   * the login view.
   */
  private sessionRevokedSubject = new Subject<void>();
  sessionRevoked$ = this.sessionRevokedSubject.asObservable();

  /**
   * In-memory token — the single source of truth for the active JWT in this tab.
   *
   * Why in-memory and not localStorage:
   *   localStorage is shared across all tabs. When Tab B's /activate writes a new JWT
   *   to localStorage, Tab A would immediately read Token-B on its next request. Tab A
   *   would then appear authenticated with a valid token, and the server-side blacklist
   *   of Token-A would never trigger the force-logout. The in-memory token is per-tab
   *   and is never affected by another tab writing to localStorage.
   *
   * The auth interceptor uses this first and only falls back to localStorage for the
   * very first /activate call on a fresh page load (before activate has returned).
   * After /activate returns, all subsequent requests use this in-memory value.
   */
  private _token: string | null = null;

  /**
   * Set when this tab is force-logged-out. Prevents the auth interceptor from
   * accidentally falling back to the winning tab's token in localStorage.
   * Reset on the next explicit login() call.
   */
  private _forceLoggedOut = false;

  constructor(private http: HttpClient) {}

  // -------------------------------------------------------------------------
  // Token management (used by authInterceptor in main.ts)
  // -------------------------------------------------------------------------

  getTokenForRequest(): string | null {
    if (this._forceLoggedOut) return null;
    return this._token ?? localStorage.getItem('token');
  }

  private setToken(token: string) {
    this._token = token;
    localStorage.setItem('token', token);
  }

  // -------------------------------------------------------------------------
  // Auth flows
  // -------------------------------------------------------------------------

  login(credentials: any): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/auth/login`, credentials).pipe(
      tap((res: any) => {
        if (res.accessToken) {
          this._forceLoggedOut = false;
          sessionStorage.removeItem('reason');
          this.setToken(res.accessToken);
          localStorage.setItem('role', res.role);
          localStorage.setItem('tenantId', res.tenantId);
          if (res.telephonyProvider) {
            localStorage.setItem('telephonyProvider', res.telephonyProvider);
          }
          if (res.agentId) {
            localStorage.setItem('agentId', res.agentId);
            this.setAgentId(res.agentId);
          }
          this.loginSuccessSubject.next(true);
        }
      })
    );
  }

  /**
   * Called on every app bootstrap (new tab / page refresh).
   * Implements the spec's "latest wins" step:
   *   - Validates the localStorage token with the gateway (signature only, bypasses blacklist)
   *   - user-service kicks any existing session for this user (sends WebSocket notification +
   *     blacklists the old token)
   *   - Returns a fresh JWT that becomes this tab's in-memory token
   */
  activateSession(): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/auth/activate`, {}).pipe(
      tap((res: any) => {
        if (res?.accessToken) {
          this.setToken(res.accessToken);
        }
      })
    );
  }

  /**
   * Called by the interceptor when the backend returns 403 / errorCode 403020
   * (blacklisted token — another tab called /activate after this one).
   * Also called by AppComponent when a WebSocket LogoutNotification arrives.
   *
   * We do NOT clear localStorage here because it is shared across tabs and clearing
   * it would also wipe the winning tab's reference token. Instead we:
   *   1. Set the in-memory forceLoggedOut flag so no further requests carry a token.
   *   2. Store the human-readable reason in localStorage so the login page can show it.
   *   3. Emit sessionRevoked$ so AppComponent switches to the login view.
   */
  notifyForceLogout(reason: string) {
    this._forceLoggedOut = true;
    this._token = null;
    sessionStorage.setItem('reason', reason);
    this.agentIdSubject.next('');
    this.sessionRevokedSubject.next();
  }

  logout() {
    this._token = null;
    this._forceLoggedOut = false;
    sessionStorage.removeItem('reason');
    localStorage.clear();
  }

  // -------------------------------------------------------------------------
  // Agent ID helpers
  // -------------------------------------------------------------------------

  setAgentId(id: string) {
    this.agentIdSubject.next(id);
  }

  get currentAgentId() {
    return this.agentIdSubject.value;
  }

  get tenantId() {
    return localStorage.getItem('tenantId');
  }

  // -------------------------------------------------------------------------
  // API calls (headers are attached by authInterceptor in main.ts)
  // -------------------------------------------------------------------------

  private getHeaders() {
    const token = this.getTokenForRequest();
    return {
      headers: new HttpHeaders({
        'Authorization': `Bearer ${token ?? ''}`
      })
    };
  }

  updateAgentStatus(agentId: string, status: 'available' | 'busy' | 'logout' | 'login'): Observable<any> {
    const endpoint = `${this.GATEWAY_URL}/api/v1/agents/${agentId}/${status}`;
    return this.http.post(endpoint, {}, this.getHeaders());
  }

  getAgentState(agentId: string): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/state`, this.getHeaders());
  }

  heartbeatAgent(agentId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/heartbeat`, {}, this.getHeaders());
  }

  createCall(tenantId: string, skill: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/calls`, {
      tenantId,
      requiredSkills: [skill]
    }, this.getHeaders());
  }

  updateCallStatus(callId: string, status: string): Observable<any> {
    let endpoint = 'start';
    if (status === 'COMPLETED') endpoint = 'complete';
    else if (status === 'REJECTED') endpoint = 'reject';

    const options: any = this.getHeaders();
    if (this.tenantId) {
      options.headers = (options.headers as HttpHeaders).set('X-Tenant-Id', this.tenantId);
    }
    return this.http.post(`${this.GATEWAY_URL}/api/v1/calls/${callId}/${endpoint}`, {}, options);
  }

  getCall(callId: string, tenantId: string): Observable<any> {
    const options: any = this.getHeaders();
    options.headers = (options.headers as HttpHeaders).set('X-Tenant-Id', tenantId);
    return this.http.get(`${this.GATEWAY_URL}/api/v1/calls/${callId}`, options);
  }

  getMetrics(tenantId: string): Observable<any> {
    const options: any = this.getHeaders();
    if (tenantId) {
      options.headers = (options.headers as HttpHeaders).set('X-Tenant-Id', tenantId);
    }
    return this.http.get(`${this.GATEWAY_URL}/api/v1/analytics/${tenantId}/metrics`, options);
  }

  createAgent(tenantId: string, agentData: any): Observable<any> {
    const options: any = this.getHeaders();
    options.headers = (options.headers as HttpHeaders).set('X-Tenant-Id', tenantId);
    return this.http.post(`${this.GATEWAY_URL}/api/v1/users/agents`, agentData, options);
  }
}
