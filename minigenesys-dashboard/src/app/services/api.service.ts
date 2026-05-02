import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders } from '@angular/common/http';
import { Observable, tap, BehaviorSubject } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class ApiService {
  private readonly GATEWAY_URL = 'http://localhost:8080';
  
  private agentIdSubject = new BehaviorSubject<string>('');
  agentId$ = this.agentIdSubject.asObservable();

  private loginSuccessSubject = new BehaviorSubject<boolean>(false);
  loginSuccess$ = this.loginSuccessSubject.asObservable();

  constructor(private http: HttpClient) {}

  private getHeaders() {
    const token = localStorage.getItem('token');
    return {
      headers: new HttpHeaders({
        'Authorization': `Bearer ${token}`
      })
    };
  }

  setAgentId(id: string) {
    this.agentIdSubject.next(id);
  }

  get currentAgentId() {
    return this.agentIdSubject.value;
  }

  get tenantId() {
    return localStorage.getItem('tenantId');
  }

  private isRefreshing = false;

  login(credentials: any): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/auth/login`, credentials).pipe(
      tap((res: any) => {
        if (res.accessToken) {
          localStorage.setItem('token', res.accessToken);
          localStorage.setItem('role', res.role);
          localStorage.setItem('tenantId', res.tenantId);
          if (res.agentId) {
            localStorage.setItem('agentId', res.agentId);
            this.setAgentId(res.agentId);
          }
          this.loginSuccessSubject.next(true);
        }
      })
    );
  }

  logout() {
    localStorage.clear();
  }

  updateAgentStatus(agentId: string, status: 'available' | 'busy' | 'logout'): Observable<any> {
    const endpoint = `${this.GATEWAY_URL}/api/v1/agents/${agentId}/${status}`;
    return this.http.post(endpoint, {}, {
      headers: this.getHeaders()
    });
  }

  // Helper to handle 401s globally if needed, or just let components handle it.
  ensureAuth(): Observable<any> {
    const token = localStorage.getItem('token');
    if (!token) throw new Error('Not authenticated');
    return new Observable(obs => obs.next(token));
  }

  // Agent API
  loginAgent(agentId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/login`, {}, this.getHeaders());
  }

  logoutAgent(agentId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/logout`, {}, this.getHeaders());
  }

  setAgentState(agentId: string, status: string): Observable<any> {
    const endpoint = status.toLowerCase() === 'available' ? 'available' : 'busy';
    return this.http.post(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/${endpoint}`, {}, this.getHeaders());
  }

  getAgentState(agentId: string): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/state`, this.getHeaders());
  }

  heartbeatAgent(agentId: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/agents/${agentId}/heartbeat`, {}, this.getHeaders());
  }

  // Call API
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
      options.headers = options.headers.set('X-Tenant-Id', this.tenantId);
    }
    return this.http.post(`${this.GATEWAY_URL}/api/v1/calls/${callId}/${endpoint}`, {}, options);
  }

  getCall(callId: string, tenantId: string): Observable<any> {
    const options: any = this.getHeaders();
    options.headers = options.headers.set('X-Tenant-Id', tenantId);
    return this.http.get(`${this.GATEWAY_URL}/api/v1/calls/${callId}`, options);
  }

  // Analytics API
  getMetrics(tenantId: string): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/analytics/${tenantId}/metrics`, this.getHeaders());
  }

  // Admin APIs
  createAgent(tenantId: string, agentData: any): Observable<any> {
    const options: any = this.getHeaders();
    options.headers = options.headers.set('X-Tenant-Id', tenantId);
    return this.http.post(`${this.GATEWAY_URL}/api/v1/users/agents`, agentData, options);
  }
}
