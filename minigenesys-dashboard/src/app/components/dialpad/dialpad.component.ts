import { Component, OnInit, Output, EventEmitter } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { FreeswitchWebRtcService } from '../../services/freeswitch-webrtc.service';
import { ApiService } from '../../services/api.service';
import { SessionStateService } from '../../services/session-state.service';

@Component({
  selector: 'app-dialpad',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './dialpad.component.html',
  styleUrls: ['./dialpad.component.css']
})
export class DialpadComponent implements OnInit {
  @Output() close = new EventEmitter<void>();
  
  phoneNumber: string = '';
  isDialing: boolean = false;
  callInProgress: boolean = false;
  errorMessage: string = '';

  dialpadButtons = [
    ['1', '2', '3'],
    ['4', '5', '6'],
    ['7', '8', '9'],
    ['*', '0', '#'],
    ['+']
  ];

  constructor(
    private freeswitchWebRtc: FreeswitchWebRtcService,
    private apiService: ApiService,
    private sessionState: SessionStateService
  ) {}

  ngOnInit(): void {}

  onDigitClick(digit: string): void {
    this.phoneNumber += digit;
    this.errorMessage = '';
    
    // Send DTMF if there's an active call
    const activeCall = this.sessionState.call;
    if (activeCall?.status === 'IN_PROGRESS') {
      this.freeswitchWebRtc.sendDTMF(digit);
    }
  }

  onBackspace(): void {
    this.phoneNumber = this.phoneNumber.slice(0, -1);
    this.errorMessage = '';
  }

  onClear(): void {
    this.phoneNumber = '';
    this.errorMessage = '';
  }

  async onDial(): Promise<void> {
    if (!this.phoneNumber || this.phoneNumber.length < 10) {
      this.errorMessage = 'Please enter a valid phone number';
      return;
    }

    this.isDialing = true;
    this.callInProgress = true;
    this.errorMessage = '';

    try {
      const agentId = this.sessionState.agent.agentId || this.apiService.currentAgentId;
      if (!agentId) {
        throw new Error('Agent not logged in');
      }

      const telephonyProvider = localStorage.getItem('telephonyProvider') || 'FREESWITCH';

      const callData = {
        toNumber: this.normalizeToE164(this.phoneNumber),
        agentId,
        telephonyProvider
      };

      console.log('Creating outbound call:', callData);
      const response = await this.apiService.createOutboundCall(callData).toPromise();
      console.log('Outbound call created:', response);

      // The backend will trigger FreeSWITCH to originate the call
      // The agent will receive the call via WebRTC, which will auto-answer
      
      // Show call progress for 3 seconds before allowing new calls
      setTimeout(() => {
        this.callInProgress = false;
        this.phoneNumber = '';
      }, 3000);
      
    } catch (error: any) {
      console.error('Failed to initiate outbound call:', error);
      this.errorMessage = error.error?.message || 'Failed to initiate call';
      this.callInProgress = false;
    } finally {
      this.isDialing = false;
    }
  }

  onClose(): void {
    this.close.emit();
  }

  formatPhoneNumber(): string {
    if (!this.phoneNumber) return '';
    
    // Simple formatting for US numbers
    const cleaned = this.phoneNumber.replace(/\D/g, '');
    if (cleaned.length <= 3) {
      return cleaned;
    } else if (cleaned.length <= 6) {
      return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3)}`;
    } else if (cleaned.length <= 10) {
      return `(${cleaned.slice(0, 3)}) ${cleaned.slice(3, 6)}-${cleaned.slice(6)}`;
    } else {
      return `+${cleaned.slice(0, cleaned.length - 10)} (${cleaned.slice(-10, -7)}) ${cleaned.slice(-7, -4)}-${cleaned.slice(-4)}`;
    }
  }

  private normalizeToE164(number: string): string {
    const digits = number.replace(/\D/g, '');
    if (number.startsWith('+')) return `+${digits}`;
    if (digits.length === 10) return `+1${digits}`;
    if (digits.length === 11 && digits.startsWith('1')) return `+${digits}`;
    return `+${digits}`;
  }
}
