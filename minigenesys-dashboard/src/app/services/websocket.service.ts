import { Injectable, NgZone } from '@angular/core';
import { Client } from '@stomp/stompjs';
import * as _SockJS from 'sockjs-client';
import { Observable, Subject } from 'rxjs';

const SockJS = (_SockJS as any).default || _SockJS;

@Injectable({
  providedIn: 'root'
})
export class WebsocketService {
  private stompClient: Client;

  /** Tenant-wide events (call events, agent events, etc.) */
  private eventsSubject = new Subject<any>();
  public events$ = this.eventsSubject.asObservable();

  /**
   * User-specific events delivered via /topic/{tenantId}/user/{userId}.
   * Used for LogoutNotification (spec §4.6 / §5.2 Path A).
   */
  private userEventsSubject = new Subject<any>();
  public userEvents$: Observable<any> = this.userEventsSubject.asObservable();

  private connected = false;
  private currentTenantSubscription: any = null;
  private currentUserSubscription: any = null;

  /** Deferred until after the STOMP connection is established. */
  private pendingUserChannelId: string | null = null;

  constructor(private zone: NgZone) {
    this.stompClient = new Client({
      webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
      debug: (msg: string) => console.log('[STOMP DEBUG]', msg),
      reconnectDelay: 5000,
      beforeConnect: () => {
        const token = localStorage.getItem('token');
        if (token) {
          this.stompClient.connectHeaders = {
            'Authorization': `Bearer ${token}`
          };
        }
      }
    });

    this.stompClient.onConnect = (frame) => {
      console.log('[Websocket] Connected: ' + frame);
      this.connected = true;
      this.subscribeToTenantEvents();

      // If a user channel was requested before the connection was ready, subscribe now.
      if (this.pendingUserChannelId) {
        this._doSubscribeToUserChannel(this.pendingUserChannelId);
        this.pendingUserChannelId = null;
      }
    };

    this.stompClient.onDisconnect = () => {
      console.log('[Websocket] Disconnected');
      this.connected = false;
      this.currentTenantSubscription = null;
      this.currentUserSubscription = null;
    };

    this.stompClient.onStompError = (frame) => {
      console.error('Broker reported error: ' + frame.headers['message']);
      console.error('Additional details: ' + frame.body);

      if (frame.headers?.['message']?.includes('Unauthorized')) {
        console.warn('Unauthorized WebSocket connection, clearing session and redirecting to login');
        this.zone.run(() => {
          localStorage.clear();
          window.location.href = '/login';
        });
      }
    };
  }

  /** Subscribe to the shared tenant-wide event topic. */
  public subscribeToTenantEvents() {
    const tenantId = localStorage.getItem('tenantId');
    if (!tenantId) {
      console.warn('[Websocket] Cannot subscribe to tenant events: No tenantId found');
      return;
    }
    if (!this.stompClient.connected) {
      console.warn('[Websocket] Not connected yet — will subscribe on connect.');
      return;
    }
    if (this.currentTenantSubscription) {
      this.currentTenantSubscription.unsubscribe();
    }
    console.log(`[Websocket] Subscribing to /topic/events/${tenantId}`);
    this.currentTenantSubscription = this.stompClient.subscribe(
      `/topic/events/${tenantId}`,
      (message) => {
        if (message.body) {
          this.zone.run(() => {
            const payload = JSON.parse(message.body);
            console.log('[Websocket] Tenant event received:', payload);
            this.eventsSubject.next(payload);
          });
        }
      }
    );
  }

  /**
   * Subscribe to the user's personal notification channel.
   * Must be called AFTER /auth/activate returns (spec §5.2) so that the
   * LogoutNotification from the /activate call is NOT received by the new tab —
   * only the old tab (still subscribed) receives it.
   *
   * Topic: /topic/{tenantId}/user/{userId}
   */
  public subscribeToUserChannel(userId: string) {
    if (!userId) return;

    if (this.stompClient.connected) {
      this._doSubscribeToUserChannel(userId);
    } else {
      // Defer — onConnect will call this when the socket is ready.
      this.pendingUserChannelId = userId;
    }
  }

  private _doSubscribeToUserChannel(userId: string) {
    const tenantId = localStorage.getItem('tenantId');
    if (!tenantId || !userId) return;

    if (this.currentUserSubscription) {
      this.currentUserSubscription.unsubscribe();
    }
    const destination = `/topic/${tenantId}/user/${userId}`;
    console.log(`[Websocket] Subscribing to user channel ${destination}`);
    this.currentUserSubscription = this.stompClient.subscribe(destination, (message) => {
      if (message.body) {
        this.zone.run(() => {
          const payload = JSON.parse(message.body);
          console.log('[Websocket] User event received:', payload);
          this.userEventsSubject.next(payload);
        });
      }
    });
  }

  connect() {
    const token = localStorage.getItem('token');
    if (!token) return;
    if (this.stompClient.active) return;

    this.stompClient.connectHeaders = {
      'Authorization': `Bearer ${token}`
    };
    this.stompClient.activate();
  }

  disconnect() {
    this.stompClient.deactivate();
    this.connected = false;
    this.currentTenantSubscription = null;
    this.currentUserSubscription = null;
  }
}
