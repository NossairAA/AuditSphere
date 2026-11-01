"use client";
import { use } from "react";
import ActionDetail from "@/components/action-detail";

export default function Page({ params }: { params: Promise<{ id: string }> }) {
  const { id } = use(params);
  return <ActionDetail key={id} id={id} />;
}
