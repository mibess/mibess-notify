import { bootstrapApplication } from '@angular/platform-browser';
import { provideHttpClient, withXsrfConfiguration } from '@angular/common/http';
import { AppComponent } from './app/app';
bootstrapApplication(AppComponent, { providers: [provideHttpClient(withXsrfConfiguration({cookieName:'XSRF-TOKEN',headerName:'X-XSRF-TOKEN'}))] }).catch(() => { document.body.textContent='Não foi possível iniciar o painel. Recarregue a página.'; });
