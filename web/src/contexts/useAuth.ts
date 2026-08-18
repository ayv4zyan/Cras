import { useContext } from "react";
import { AuthContext, type AuthContextValue } from "./auth-context-def";

export function useAuth(): AuthContextValue {
  const context = useContext(AuthContext);
  if (!context) {
    throw new Error("useAuth must be used within an AuthProvider");
  }
  return context;
}
export type { AuthContextValue };
