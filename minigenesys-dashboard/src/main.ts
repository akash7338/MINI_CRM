import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withInterceptors } from '@angular/common/http';
import { AppComponent } from './app/app.component';
import { HttpInterceptorFn } from '@angular/common/http';
import { inject } from '@angular/core';
import { catchError, throwError } from 'rxjs';
import { ApiService } from './app/services/api.service';

/**
 * Attach the JWT to every outgoing request.
 *
 * Token storage strategy (per spec §5 + §9):
 *   ApiService._token  — in-memory per-tab token, set after login/activate
 *   localStorage.token — bootstrap seed only; a newly opened tab reads this
 *                        before calling /activate (which issues a fresh JWT)
 *
 * Why in-memory and not re-reading localStorage:
 *   When Tab B's /activate writes a new JWT to localStorage, Tab A would
 *   immediately read Token-B on its next request and appear authenticated with
 *   a valid token — the blacklist check on Token-A would never fire.
 *   ApiService._token is per-tab; it is never affected by another tab writing
 *   to localStorage. After force-logout the flag prevents even the localStorage
 *   fallback from sending a token.
 */
const authInterceptor: HttpInterceptorFn = (req, next) => {
  const api = inject(ApiService);
  const token = api.getTokenForRequest();
  if (token) {
    req = req.clone({ setHeaders: { Authorization: `Bearer ${token}` } });
  }
  return next(req);
};

/**
 * Intercept 403 / errorCode 403020 responses (blacklisted token).
 *
 * When another tab's /activate blacklists the current tab's JWT, the next
 * HTTP call from this tab hits the gateway's blacklist check and returns:
 *   HTTP 403  {"errorCode": 403020, "message": "TOKEN_EXPIRED"}
 *
 * This interceptor catches that specific shape, calls notifyForceLogout(),
 * and the AppComponent shows the login screen with a "kicked" message.
 *
 * This is the HTTP fallback path (spec §5.2 Path B). The primary path is
 * the WebSocket LogoutNotification (spec §5.2 Path A), which is handled in
 * AppComponent's ws.userEvents$ subscription.
 */
const forceLogoutInterceptor: HttpInterceptorFn = (req, next) => {
  const api = inject(ApiService);
  return next(req).pipe(
    catchError(error => {
      if (error.status === 403 && error.error?.errorCode === 403020) {
        console.warn('[Session] Token blacklisted (403020) — forcing logout.');
        api.notifyForceLogout('TOKEN_EXPIRED');
      }
      return throwError(() => error);
    })
  );
};

bootstrapApplication(AppComponent, {
  providers: [
    provideHttpClient(withInterceptors([authInterceptor, forceLogoutInterceptor]))
  ]
}).catch(err => console.error(err));
