import { Component, input } from '@angular/core';
const icons:Record<string,string>={
 dashboard:'M3 3h7v7H3z M14 3h7v7h-7z M3 14h7v7H3z M14 14h7v7h-7z',
 apps:'M4 5h16v14H4z M8 2v6 M16 2v6 M4 10h16',
 events:'M13 2 4 14h7l-1 8 10-12h-7z',
 rules:'M6 3v12a4 4 0 0 0 4 4h8 M6 7h12 M15 4l3 3-3 3 M15 16l3 3-3 3',
 contacts:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M18 8a3 3 0 0 1 0 6 M22 21v-2a4 4 0 0 0-3-3.8',
 groups:'M16 21v-2a4 4 0 0 0-4-4H6a4 4 0 0 0-4 4v2 M13 7a4 4 0 1 1-8 0 4 4 0 0 1 8 0 M18 5h4 M20 3v4',
 channels:'M21 11.5a8.4 8.4 0 0 1-.9 3.8 8.5 8.5 0 0 1-7.6 4.7 8.4 8.4 0 0 1-3.8-.9L3 21l1.9-5.7a8.4 8.4 0 0 1-.9-3.8 8.5 8.5 0 0 1 4.7-7.6 8.4 8.4 0 0 1 3.8-.9h.5a8.5 8.5 0 0 1 8 8v.5z',
 templates:'M14 2H6a2 2 0 0 0-2 2v16a2 2 0 0 0 2 2h12a2 2 0 0 0 2-2V8z M14 2v6h6 M8 13h8 M8 17h5',
 notifications:'M18 8a6 6 0 0 0-12 0c0 7-3 7-3 9h18c0-2-3-2-3-9 M10 21h4',
 webhooks:'M6 12a4 4 0 1 0 4 4 M18 12a4 4 0 1 1-4 4 M9 8a4 4 0 1 1 6 0 M10 7 6 16 M14 7l4 9 M6 16h12',
 audit:'M9 3H5v18h14V3h-4 M9 2h6v4H9z M8 10h8 M8 14h8 M8 18h5',
 settings:'M12 8a4 4 0 1 0 0 8 4 4 0 0 0 0-8 M12 2v3 M12 19v3 M2 12h3 M19 12h3 M5 5l2 2 M17 17l2 2 M5 19l2-2 M17 7l2-2',
 arrow:'M5 12h14 M14 7l5 5-5 5',plus:'M12 5v14 M5 12h14',search:'M21 21l-5-5 M18 10a8 8 0 1 1-16 0 8 8 0 0 1 16 0',check:'M5 12l4 4L19 6',close:'m6 6 12 12 M6 18 18 6',chevron:'m9 5 7 7-7 7',refresh:'M20 7a8 8 0 1 0 1 8 M20 2v6h-6',logout:'M9 5H4v14h5 M10 12h11 M17 8l4 4-4 4',menu:'M4 6h16 M4 12h16 M4 18h16',key:'M15 7a5 5 0 1 0 0 .1 M12 11l9 9 M17 16l3-3',clock:'M12 8v4l3 2 M22 12a10 10 0 1 1-20 0 10 10 0 0 1 20 0',alert:'M12 3 2 21h20z M12 9v5 M12 17v.5',shield:'M12 2 3 6v6c0 5 9 10 9 10s9-5 9-10V6z M8 12l3 3 5-6',copy:'M9 9h12v12H9z M5 15H3V3h12v2'
};
@Component({selector:'app-icon',standalone:true,template:'<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="1.6" stroke-linecap="round" stroke-linejoin="round" aria-hidden="true"><path [attr.d]="path()"/></svg>',styles:':host{display:inline-flex;width:20px;height:20px;flex-shrink:0}svg{width:100%;height:100%}'})
export class IconComponent {name=input('dashboard');path(){return icons[this.name()]||icons['events'];}}
