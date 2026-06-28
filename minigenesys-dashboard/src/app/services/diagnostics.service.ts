import { Injectable } from '@angular/core';
import { HttpClient, HttpHeaders, HttpParams } from '@angular/common/http';
import { Observable } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class DiagnosticsService {
  private readonly GATEWAY_URL = 'http://localhost:8080';

  constructor(private http: HttpClient) {}

  private getHeaders() {
    const token = localStorage.getItem('token');
    return {
      headers: new HttpHeaders({
        'Authorization': `Bearer ${token}`
      })
    };
  }

  getHealth(): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/diagnostics/health`, this.getHeaders());
  }

  getPublicHealth(): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/diagnostics/public/healthz`);
  }

  getSipDiagnostics(): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/diagnostics/sip`, this.getHeaders());
  }

  getActiveCalls(): Observable<any> {
    return this.http.get(`${this.GATEWAY_URL}/api/v1/diagnostics/calls`, this.getHeaders());
  }

  getLogServices(): Observable<any[]> {
    return this.http.get<any[]>(`${this.GATEWAY_URL}/api/v1/diagnostics/logs/services`, this.getHeaders());
  }

  getLogs(service: string, level?: string, lines: number = 100): Observable<any> {
    let params = new HttpParams().set('service', service).set('lines', lines.toString());
    if (level) {
      params = params.set('level', level);
    }
    return this.http.get(`${this.GATEWAY_URL}/api/v1/diagnostics/logs`, {
      ...this.getHeaders(),
      params
    });
  }

  getLogStreamUrl(service: string, level?: string): string {
    let url = `${this.GATEWAY_URL}/api/v1/diagnostics/logs/stream?service=${encodeURIComponent(service)}`;
    if (level) {
      url += `&level=${encodeURIComponent(level)}`;
    }
    return url;
  }

  stopService(name: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/diagnostics/services/${name}/stop`, {}, this.getHeaders());
  }

  startService(name: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/diagnostics/services/${name}/start`, {}, this.getHeaders());
  }

  restartService(name: string): Observable<any> {
    return this.http.post(`${this.GATEWAY_URL}/api/v1/diagnostics/services/${name}/restart`, {}, this.getHeaders());
  }
}
