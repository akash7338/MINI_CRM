import { Injectable, NgZone } from '@angular/core';
import { Client } from '@stomp/stompjs';
import * as _SockJS from 'sockjs-client';
import { Observable, Subject } from 'rxjs';

// Handling SockJS in ESBuild/Angular environments
const SockJS = (_SockJS as any).default || _SockJS;

@Injectable({
  providedIn: 'root'
})
export class WebsocketService {
  private stompClient: Client;
  private eventsSubject = new Subject<any>();
  public events$ = this.eventsSubject.asObservable();
  
  private connected = false;
  private currentSubscription: any = null;

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
      // When we connect, check if we have a tenantId to subscribe to
      this.subscribeToTenantEvents();
    };

    this.stompClient.onDisconnect = () => {
      console.log('[Websocket] Disconnected');
      this.connected = false;
      this.currentSubscription = null;
    };

    this.stompClient.onStompError = (frame) => {
      console.error('Broker reported error: ' + frame.headers['message']);
      console.error('Additional details: ' + frame.body);
      
      if (frame.headers && frame.headers['message'] && frame.headers['message'].includes('Unauthorized')) {
        console.warn('Unauthorized WebSocket connection, clearing session and redirecting to login');
        this.zone.run(() => {
          localStorage.clear();
          window.location.href = '/login';
        });
      }
    };
  }

  public subscribeToTenantEvents() {
    const tenantId = localStorage.getItem('tenantId');
    if (!tenantId) {
      console.warn('[Websocket] Cannot subscribe: No tenantId found in storage');
      return;
    }

    // Check if the client is actually connected to the broker
    if (!this.stompClient.connected) {
      console.warn('[Websocket] Not connected yet. Subscription will happen automatically on connection.');
      return;
    }

    // Unsubscribe from previous tenant if any
    if (this.currentSubscription) {
      console.log('[Websocket] Unsubscribing from previous tenant');
      this.currentSubscription.unsubscribe();
    }

    console.log(`[Websocket] Subscribing to /topic/events/${tenantId}`);
    this.currentSubscription = this.stompClient.subscribe(`/topic/events/${tenantId}`, (message) => {
      if (message.body) {
        this.zone.run(() => {
          const payload = JSON.parse(message.body);
          console.log('[Websocket] Event received:', payload);
          this.eventsSubject.next(payload);
        });
      }
    });
  }

  connect() {
    const token = localStorage.getItem('token');
    if (!token) return;

    // Use stompClient.active to check if we have already called activate()
    if (this.stompClient.active) return;
    
    this.stompClient.connectHeaders = {
      'Authorization': `Bearer ${token}`
    };

    this.stompClient.activate();
  }

  disconnect() {
    this.stompClient.deactivate();
    this.connected = false;
  }
}
