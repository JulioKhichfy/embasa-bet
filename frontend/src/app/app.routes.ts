import { Routes } from '@angular/router';
import { DashboardComponent } from './components/dashboard.component';
import { CadastrosComponent } from './components/cadastros.component';
import { ClubeDetalheComponent } from './components/clube-detalhe.component';
import { ComparacaoComponent } from './components/comparacao.component';
import { AnotacoesComponent } from './components/anotacoes.component';
import { ApostasComponent } from './components/apostas.component';
import { BacktestComponent } from './components/backtest.component';
// ...

export const routes: Routes = [
  { path: '', redirectTo: 'dashboard', pathMatch: 'full' },
  { path: 'dashboard', component: DashboardComponent },
  { path: 'cadastros', component: CadastrosComponent },
  { path: 'anotacoes', component: AnotacoesComponent },
  { path: 'apostas', component: ApostasComponent },
  { path: 'backtest', component: BacktestComponent },       
  { path: 'clube/:id', component: ClubeDetalheComponent },
  { path: 'comparacao', component: ComparacaoComponent },
  { path: 'backtest', component: BacktestComponent },
  { path: '**', redirectTo: 'dashboard' }
];