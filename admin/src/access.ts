import type { InitialState } from './app';

export default (initialState: InitialState | undefined) => ({
  canAdmin: initialState?.currentUser?.role === 'admin',
  canDoctor: initialState?.currentUser?.role === 'doctor',
});
