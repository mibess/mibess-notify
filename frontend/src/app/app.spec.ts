import { describe, it, expect } from 'vitest';
import { HttpErrorResponse } from '@angular/common/http';
import { errorText } from './api';
import { LABELS } from './app';
describe('Delivery and API feedback',()=>{
  it('distinguishes sent, delivered and read',()=>{expect(LABELS['SENT']).toBe('Enviada');expect(LABELS['DELIVERED']).toBe('Entregue');expect(LABELS['READ']).toBe('Lida');});
  it('displays backend validation without exposing raw errors',()=>{expect(errorText(new HttpErrorResponse({status:400,error:{detail:'Consentimento obrigatório'}}))).toBe('Consentimento obrigatório');expect(errorText(new HttpErrorResponse({status:500,error:'SQL credentials'}))).not.toContain('SQL');});
  it('explains permissions and connectivity',()=>{expect(errorText(new HttpErrorResponse({status:403}))).toContain('permissão');expect(errorText(new HttpErrorResponse({status:0}))).toContain('conectar');});
});
