// Same drawing as public/brand/auditsphere-mark.svg and app/icon.svg.
export function BrandMark() {
  return (
    <svg className="brand-mark" viewBox="0 0 64 64" aria-hidden="true">
      <rect width="64" height="64" rx="14" fill="#d9e7c1" />
      <g
        fill="none"
        stroke="#2b4736"
        strokeWidth="3.4"
        strokeLinecap="round"
        strokeLinejoin="round"
      >
        <path d="M32 15.5 48.5 23.5 32 31.5 15.5 23.5Z" />
        <path d="M15.5 32 32 40 48.5 32" />
        <path d="M15.5 40.5 32 48.5 48.5 40.5" />
      </g>
    </svg>
  );
}
export function BrandLockup() {
  return (
    <>
      <BrandMark />
      <span>
        AuditSphere<span className="brand-dot">.</span>
      </span>
    </>
  );
}
