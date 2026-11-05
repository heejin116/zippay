import type { ButtonHTMLAttributes, InputHTMLAttributes, ReactNode } from 'react'

export function Button({ className = '', ...props }: ButtonHTMLAttributes<HTMLButtonElement>) {
  return (
    <button
      className={`inline-flex h-10 items-center justify-center rounded-md bg-neutral-900 px-4 text-sm font-medium text-white transition hover:bg-neutral-700 disabled:cursor-not-allowed disabled:opacity-50 ${className}`}
      {...props}
    />
  )
}

type FieldProps = InputHTMLAttributes<HTMLInputElement> & { label: string }

export function Field({ label, id, ...props }: FieldProps) {
  return (
    <label htmlFor={id} className="block">
      <span className="mb-1.5 block text-sm font-medium text-neutral-700">{label}</span>
      <input
        id={id}
        className="h-10 w-full rounded-md border border-neutral-300 bg-white px-3 text-sm outline-none transition focus:border-neutral-900 focus:ring-1 focus:ring-neutral-900"
        {...props}
      />
    </label>
  )
}

export function ErrorMessage({ error }: { error: unknown }) {
  if (!error) return null
  const message = error instanceof Error ? error.message : '알 수 없는 오류가 발생했습니다.'
  return (
    <p role="alert" className="rounded-md border border-red-200 bg-red-50 px-3 py-2 text-sm text-red-700">
      {message}
    </p>
  )
}

export function Card({ children, className = '' }: { children: ReactNode; className?: string }) {
  return <div className={`rounded-lg border border-neutral-200 bg-white p-5 ${className}`}>{children}</div>
}

export function Spinner() {
  return (
    <div className="flex justify-center py-16">
      <div className="h-6 w-6 animate-spin rounded-full border-2 border-neutral-300 border-t-neutral-900" />
    </div>
  )
}
