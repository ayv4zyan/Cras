import React, { useEffect, useState, useCallback, useMemo } from "react";
import type { Session, SupabaseClient } from "@supabase/supabase-js";
import { supabase as defaultSupabase } from "../config/supabase";
import { AuthContext, type AuthContextValue } from "./auth-context-def";

export interface AuthProviderProps {
  readonly children: React.ReactNode;
  readonly client?: SupabaseClient;
}

function normalizeError(err: unknown): Error {
  return err instanceof Error ? err : new Error(String(err));
}

export function AuthProvider({
  children,
  client = defaultSupabase,
}: AuthProviderProps): React.JSX.Element {
  const [session, setSession] = useState<Session | null>(null);
  const [isLoading, setIsLoading] = useState<boolean>(true);
  const [error, setError] = useState<Error | null>(null);

  useEffect(() => {
    let isMounted = true;

    async function initSession() {
      try {
        const { data, error: sessionError } = await client.auth.getSession();
        if (sessionError) {
          throw sessionError;
        }
        if (isMounted) {
          setSession(data.session);
        }
      } catch (err) {
        if (isMounted) {
          setError(normalizeError(err));
        }
      } finally {
        if (isMounted) {
          setIsLoading(false);
        }
      }
    }

    initSession();

    const {
      data: { subscription },
    } = client.auth.onAuthStateChange((_event, currentSession) => {
      if (isMounted) {
        setSession(currentSession);
        setIsLoading(false);
      }
    });

    return () => {
      isMounted = false;
      subscription.unsubscribe();
    };
  }, [client]);

  const signInWithGoogle = useCallback(async () => {
    setError(null);
    try {
      const { error: signInError } = await client.auth.signInWithOAuth({
        provider: "google",
        options: {
          redirectTo:
            typeof window !== "undefined" ? window.location.origin : undefined,
        },
      });
      if (signInError) {
        throw signInError;
      }
    } catch (err) {
      const errorObj = normalizeError(err);
      setError(errorObj);
      throw errorObj;
    }
  }, [client]);

  const signOut = useCallback(async () => {
    setError(null);
    try {
      const { error: signOutError } = await client.auth.signOut();
      if (signOutError) {
        throw signOutError;
      }
    } catch (err) {
      const errorObj = normalizeError(err);
      setError(errorObj);
      throw errorObj;
    }
  }, [client]);

  const user = session?.user ?? null;

  const value = useMemo<AuthContextValue>(
    () => ({
      session,
      user,
      isLoading,
      error,
      signInWithGoogle,
      signOut,
    }),
    [session, user, isLoading, error, signInWithGoogle, signOut],
  );

  return <AuthContext.Provider value={value}>{children}</AuthContext.Provider>;
}
