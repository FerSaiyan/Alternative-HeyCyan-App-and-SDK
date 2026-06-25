import { NextResponse } from "next/server";
import { getRequestOrigin } from "@/lib/request-origin";

export async function POST(request: Request) {
  const redirectUrl = new URL("/signin", getRequestOrigin(request));

  const response = NextResponse.redirect(redirectUrl, { status: 303 });
  response.cookies.delete("carelens_user_id");
  response.cookies.delete("carelens_booking_id");

  return response;
}
