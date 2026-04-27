import { Injectable } from '@angular/core';
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

  constructor() {
    this.stompClient = new Client({
      webSocketFactory: () => new SockJS('http://localhost:8080/ws'),
      debug: (msg: string) => console.log(msg),
      reconnectDelay: 5000,
    });

    this.stompClient.onConnect = (frame) => {
      console.log('Connected: ' + frame);
      const tenantId = localStorage.getItem('tenantId');
      if (tenantId) {
        this.stompClient.subscribe(`/topic/events/${tenantId}`, (message) => {
          if (message.body) {
            this.eventsSubject.next(JSON.parse(message.body));
          }
        });
      }
    };

    this.stompClient.onStompError = (frame) => {
      console.error('Broker reported error: ' + frame.headers['message']);
      console.error('Additional details: ' + frame.body);
    };
  }

  private connected = false;

  connect() {
    const token = localStorage.getItem('token');
    if (!token) return;

    if (this.connected) return; // Prevent duplicate connections
    
    // Set headers before activation
    this.stompClient.connectHeaders = {
      'Authorization': `Bearer ${token}`
    };

    this.connected = true;
    this.stompClient.activate();
  }

  disconnect() {
    this.connected = false;
    this.stompClient.deactivate();
  }
}
