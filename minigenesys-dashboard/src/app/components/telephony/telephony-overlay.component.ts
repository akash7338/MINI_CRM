import { Component, OnInit } from '@angular/core';
import { TelephonyService } from '../../services/telephony.service';
import { ApiService } from '../../services/api.service';
import { SessionStateService } from '../../services/session-state.service';
import { CommonModule } from '@angular/common';
import { Call } from '@twilio/voice-sdk';

@Component({
  selector: 'app-telephony-overlay',
  standalone: true,
  imports: [CommonModule],
  template: `
    <!-- Incoming Call Popup -->
    <div *ngIf="incomingCall" class="call-popup">
      <div class="popup-header">
        <span class="pulse-icon"></span>
        Incoming Call
      </div>
      <div class="popup-body">
        <div class="caller-info">
          <label>From:</label>
          <span>{{ incomingCall.parameters['From'] }}</span>
        </div>
        <div class="status-badge">Ringing...</div>
      </div>
      <div class="popup-actions">
        <button (click)="onAccept()" class="btn-accept">Accept</button>
        <button (click)="onReject()" class="btn-reject">Reject</button>
      </div>
    </div>

    <!-- Active Call Banner -->
    <div *ngIf="activeCall" class="active-call-banner">
      <div class="banner-content">
        <span class="active-icon"></span>
        <span class="active-label">Active Call:</span>
        <span class="active-number">{{ activeCall.parameters['From'] }}</span>
        <span class="timer">{{ duration }}s</span>
      </div>
      <button (click)="onHangup()" class="btn-hangup">Hang Up</button>
    </div>
  `,
  styles: [`
    .call-popup {
      position: fixed;
      bottom: 30px;
      right: 30px;
      width: 320px;
      background: rgba(255, 255, 255, 0.95);
      backdrop-filter: blur(10px);
      border-radius: 16px;
      box-shadow: 0 10px 40px rgba(0,0,0,0.2);
      border: 1px solid rgba(255, 255, 255, 0.2);
      z-index: 1000;
      animation: slideIn 0.4s cubic-bezier(0.175, 0.885, 0.32, 1.275);
      padding: 20px;
    }

    @keyframes slideIn {
      from { transform: translateY(100px); opacity: 0; }
      to { transform: translateY(0); opacity: 1; }
    }

    .popup-header {
      font-weight: 700;
      font-size: 1.1rem;
      color: #1a1a1a;
      display: flex;
      align-items: center;
      gap: 10px;
      margin-bottom: 15px;
    }

    .pulse-icon {
      width: 12px;
      height: 12px;
      background: #ff4757;
      border-radius: 50%;
      box-shadow: 0 0 0 0 rgba(255, 71, 87, 0.7);
      animation: pulse 1.5s infinite;
    }

    @keyframes pulse {
      0% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(255, 71, 87, 0.7); }
      70% { transform: scale(1); box-shadow: 0 0 0 10px rgba(255, 71, 87, 0); }
      100% { transform: scale(0.95); box-shadow: 0 0 0 0 rgba(255, 71, 87, 0); }
    }

    .caller-info {
      margin-bottom: 10px;
    }

    .caller-info label {
      font-size: 0.8rem;
      color: #666;
      display: block;
    }

    .caller-info span {
      font-size: 1.2rem;
      font-weight: 600;
      color: #2f3542;
    }

    .status-badge {
      display: inline-block;
      padding: 4px 12px;
      background: #e1f5fe;
      color: #0288d1;
      border-radius: 20px;
      font-size: 0.75rem;
      font-weight: 600;
      margin-bottom: 20px;
    }

    .popup-actions {
      display: flex;
      gap: 12px;
    }

    .btn-accept {
      flex: 1;
      background: #2ed573;
      color: white;
      border: none;
      padding: 12px;
      border-radius: 12px;
      font-weight: 600;
      cursor: pointer;
      transition: transform 0.2s;
    }

    .btn-accept:hover { transform: translateY(-2px); background: #26af5f; }

    .btn-reject {
      flex: 1;
      background: #ff4757;
      color: white;
      border: none;
      padding: 12px;
      border-radius: 12px;
      font-weight: 600;
      cursor: pointer;
      transition: transform 0.2s;
    }

    .btn-reject:hover { transform: translateY(-2px); background: #e84118; }

    /* Active Call Banner */
    .active-call-banner {
      position: fixed;
      top: 20px;
      left: 50%;
      transform: translateX(-50%);
      background: #2f3542;
      color: white;
      padding: 10px 24px;
      border-radius: 100px;
      display: flex;
      align-items: center;
      gap: 20px;
      box-shadow: 0 10px 30px rgba(0,0,0,0.3);
      z-index: 1001;
      animation: slideDown 0.3s ease-out;
    }

    @keyframes slideDown {
      from { transform: translate(-50%, -100px); }
      to { transform: translate(-50%, 0); }
    }

    .banner-content {
      display: flex;
      align-items: center;
      gap: 12px;
    }

    .active-icon {
      width: 10px;
      height: 10px;
      background: #2ed573;
      border-radius: 50%;
    }

    .active-label {
      font-size: 0.8rem;
      opacity: 0.8;
    }

    .active-number {
      font-weight: 600;
    }

    .timer {
      font-family: monospace;
      background: rgba(255,255,255,0.1);
      padding: 2px 8px;
      border-radius: 4px;
    }

    .btn-hangup {
      background: #ff4757;
      color: white;
      border: none;
      padding: 6px 16px;
      border-radius: 50px;
      font-size: 0.8rem;
      font-weight: 600;
      cursor: pointer;
    }

    .btn-hangup:hover { background: #e84118; }
  `]
})
export class TelephonyOverlayComponent implements OnInit {
  incomingCall: Call | null = null;
  activeCall: Call | null = null;
  duration = 0;
  timer: any;

  constructor(
    private telephonyService: TelephonyService,
    private apiService: ApiService,
    private session: SessionStateService
  ) {}

  ngOnInit() {
    this.telephonyService.incomingCall$.subscribe(call => {
      this.incomingCall = call;
    });

    this.telephonyService.activeCall$.subscribe(call => {
      this.activeCall = call;
      if (call) {
        this.startTimer();
      } else {
        this.stopTimer();
      }
    });
  }

  startTimer() {
    this.duration = 0;
    this.timer = setInterval(() => {
      this.duration++;
    }, 1000);
  }

  stopTimer() {
    if (this.timer) {
      clearInterval(this.timer);
    }
  }

  onAccept() {
    if (this.incomingCall) {
      this.telephonyService.acceptCall(this.incomingCall);
    }
  }

  onReject() {
    if (this.incomingCall) {
      this.telephonyService.rejectCall(this.incomingCall);
    }
  }

  onHangup() {
    this.telephonyService.hangup();
    
    // Notify the backend that the call has ended so agent state resets
    const currentCallId = this.session.call.callId;
    if (currentCallId) {
      this.apiService.updateCallStatus(currentCallId, 'COMPLETED').subscribe({
        next: (res) => this.session.setCall(currentCallId, res.status),
        error: (err) => console.error('Failed to mark call as complete', err)
      });
    }
  }
}
