import { inject, Injectable } from "@angular/core";
import { HttpClient, HttpErrorResponse } from "@angular/common/http";
import { firstValueFrom } from "rxjs";

export interface Resource {
  id: string;
  code: string;
  name: string;
  enabled: boolean;
  spec: Record<string, any>;
  createdAt: string;
}
@Injectable({ providedIn: "root" })
export class Api {
  private http = inject(HttpClient);
  get<T = any>(path: string) {
    return firstValueFrom(this.http.get<T>("/api/v1/" + path));
  }
  post<T = any>(path: string, body: unknown = {}) {
    return firstValueFrom(this.http.post<T>("/api/v1/" + path, body));
  }
  put<T = any>(path: string, body: unknown) {
    return firstValueFrom(this.http.put<T>("/api/v1/" + path, body));
  }
  delete(path: string) {
    return firstValueFrom(this.http.delete("/api/v1/" + path));
  }
}
export function errorText(error: unknown): string {
  if (error instanceof HttpErrorResponse) {
    if (error.status === 401)
      return "Sua sessão expirou ou os dados de acesso são inválidos.";
    if (error.status === 403)
      return "Você não tem permissão para realizar esta ação.";
    return (
      error.error?.detail ||
      (error.status === 0
        ? "Não foi possível conectar ao servidor."
        : "Não foi possível concluir. Tente novamente.")
    );
  }
  return error instanceof Error
    ? error.message
    : "Não foi possível concluir a operação.";
}
