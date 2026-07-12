import { Component, OnInit } from '@angular/core';
import { CommonModule } from '@angular/common';
import { FormsModule } from '@angular/forms';
import { ApiService } from '../../services/api.service';

interface Contact {
  id: string;
  name: string;
  phoneNumber: string;
  email?: string;
  metadata?: string;
}

@Component({
  selector: 'app-contacts',
  standalone: true,
  imports: [CommonModule, FormsModule],
  templateUrl: './contacts.component.html',
  styleUrls: ['./contacts.component.css']
})
export class ContactsComponent implements OnInit {
  contacts: Contact[] = [];
  filteredContacts: Contact[] = [];
  searchQuery: string = '';
  
  showAddModal: boolean = false;
  showEditModal: boolean = false;
  
  newContact: Partial<Contact> = {};
  editingContact: Contact | null = null;
  
  isLoading: boolean = false;
  errorMessage: string = '';
  successMessage: string = '';

  constructor(private apiService: ApiService) {}

  ngOnInit(): void {
    this.loadContacts();
  }

  async loadContacts(): Promise<void> {
    this.isLoading = true;
    this.errorMessage = '';
    
    try {
      const response = await this.apiService.getContacts().toPromise();
      this.contacts = response || [];
      this.filteredContacts = this.contacts;
    } catch (error: any) {
      console.error('Failed to load contacts:', error);
      this.errorMessage = 'Failed to load contacts';
    } finally {
      this.isLoading = false;
    }
  }

  onSearch(): void {
    if (!this.searchQuery.trim()) {
      this.filteredContacts = this.contacts;
      return;
    }

    const query = this.searchQuery.toLowerCase();
    this.filteredContacts = this.contacts.filter(contact =>
      contact.name.toLowerCase().includes(query) ||
      contact.phoneNumber.includes(query) ||
      (contact.email && contact.email.toLowerCase().includes(query))
    );
  }

  openAddModal(): void {
    this.newContact = {};
    this.showAddModal = true;
    this.errorMessage = '';
  }

  closeAddModal(): void {
    this.showAddModal = false;
    this.newContact = {};
  }

  openEditModal(contact: Contact): void {
    this.editingContact = { ...contact };
    this.showEditModal = true;
    this.errorMessage = '';
  }

  closeEditModal(): void {
    this.showEditModal = false;
    this.editingContact = null;
  }

  async onAddContact(): Promise<void> {
    if (!this.newContact.name || !this.newContact.phoneNumber) {
      this.errorMessage = 'Name and phone number are required';
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';

    try {
      await this.apiService.createContact(this.newContact).toPromise();
      this.successMessage = 'Contact added successfully';
      setTimeout(() => this.successMessage = '', 3000);
      this.closeAddModal();
      await this.loadContacts();
    } catch (error: any) {
      console.error('Failed to add contact:', error);
      this.errorMessage = error.error?.message || 'Failed to add contact';
    } finally {
      this.isLoading = false;
    }
  }

  async onUpdateContact(): Promise<void> {
    if (!this.editingContact) return;

    if (!this.editingContact.name || !this.editingContact.phoneNumber) {
      this.errorMessage = 'Name and phone number are required';
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';

    try {
      await this.apiService.updateContact(this.editingContact.id, this.editingContact).toPromise();
      this.successMessage = 'Contact updated successfully';
      setTimeout(() => this.successMessage = '', 3000);
      this.closeEditModal();
      await this.loadContacts();
    } catch (error: any) {
      console.error('Failed to update contact:', error);
      this.errorMessage = error.error?.message || 'Failed to update contact';
    } finally {
      this.isLoading = false;
    }
  }

  async onDeleteContact(contactId: string): Promise<void> {
    if (!confirm('Are you sure you want to delete this contact?')) {
      return;
    }

    this.isLoading = true;
    this.errorMessage = '';

    try {
      await this.apiService.deleteContact(contactId).toPromise();
      this.successMessage = 'Contact deleted successfully';
      setTimeout(() => this.successMessage = '', 3000);
      await this.loadContacts();
    } catch (error: any) {
      console.error('Failed to delete contact:', error);
      this.errorMessage = error.error?.message || 'Failed to delete contact';
    } finally {
      this.isLoading = false;
    }
  }

  async onQuickDial(phoneNumber: string): Promise<void> {
    try {
      const agent = this.apiService.currentAgentId;
      if (!agent) {
        alert('Please log in as an agent first');
        return;
      }

      const callData = {
        toNumber: phoneNumber,
        agentId: agent,
        telephonyProvider: localStorage.getItem('telephonyProvider') || 'FREESWITCH'
      };

      await this.apiService.createOutboundCall(callData).toPromise();
      this.successMessage = `Calling ${phoneNumber}...`;
      setTimeout(() => this.successMessage = '', 3000);
    } catch (error: any) {
      console.error('Failed to initiate call:', error);
      this.errorMessage = error.error?.message || 'Failed to initiate call';
    }
  }
}
