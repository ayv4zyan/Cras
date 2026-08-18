import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import App from './App';

describe('Web Client Seam - App Surface', () => {
  it('renders deliberate empty Cras surface with domain navigation and inbox state', () => {
    render(<App />);

    // Brand and Operator context
    expect(screen.getByRole('heading', { level: 1, name: /cras/i })).toBeInTheDocument();
    expect(screen.getByText(/operator task space/i)).toBeInTheDocument();

    // Standard domain views
    expect(screen.getByRole('button', { name: /inbox/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /today/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /upcoming/i })).toBeInTheDocument();

    // Deliberate empty state in Inbox view
    expect(screen.getByRole('heading', { level: 2, name: /inbox/i })).toBeInTheDocument();
    expect(screen.getByText(/no tasks in inbox/i)).toBeInTheDocument();
    expect(screen.getByText(/your task space is clear/i)).toBeInTheDocument();
  });
});
