import { Injectable } from '@angular/core';
import { Client } from '@stomp/stompjs';
import SockJS from 'sockjs-client';
import { Observable, Subject } from 'rxjs';

@Injectable({
  providedIn: 'root'
})
export class WebsocketService {
  private stompClient: Client;
  private eventsSubject = new Subject<any>();
  public events$ = this.eventsSubject.asObservable();

  constructor() {
    this.stompClient = new Client({
      webSocketFactory: () => new SockJS('http://localhost:8088/ws'),
      debug: (msg: string) => console.log(msg),
      reconnectDelay: 5000,
    });

    this.stompClient.onConnect = (frame) => {
      console.log('Connected: ' + frame);
      this.stompClient.subscribe('/topic/events', (message) => {
        if (message.body) {
          this.eventsSubject.next(JSON.parse(message.body));
        }
      });
      this.stompClient.subscribe('/topic/events/tenant1', (message) => {
        if (message.body) {
          this.eventsSubject.next(JSON.parse(message.body));
        }
      });
    };

    this.stompClient.onStompError = (frame) => {
      console.error('Broker reported error: ' + frame.headers['message']);
      console.error('Additional details: ' + frame.body);
    };
  }

  connect() {
    this.stompClient.activate();
  }

  disconnect() {
    this.stompClient.deactivate();
  }
}
