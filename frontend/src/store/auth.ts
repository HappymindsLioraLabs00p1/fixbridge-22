import { create } from "zustand";
import { persist } from "zustand/middleware";
import type { TokenResponse, UserView } from "@/lib/types";

interface AuthState {
  accessToken: string | null;
  refreshToken: string | null;
  user: UserView | null;
  setAuth: (t: TokenResponse) => void;
  setTokens: (accessToken: string, refreshToken: string) => void;
  clear: () => void;
}

/** Persisted auth session (token + user). The API client reads tokens from here. */
export const useAuth = create<AuthState>()(
  persist(
    (set) => ({
      accessToken: null,
      refreshToken: null,
      user: null,
      setAuth: (t) => set({ accessToken: t.accessToken, refreshToken: t.refreshToken, user: t.user }),
      setTokens: (accessToken, refreshToken) => set({ accessToken, refreshToken }),
      clear: () => set({ accessToken: null, refreshToken: null, user: null }),
    }),
    { name: "fixbridge-auth" },
  ),
);
