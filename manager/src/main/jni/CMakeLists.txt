cmake_minimum_required(VERSION 3.22)
project(npatch_metrics LANGUAGES C)

add_library(npatch_metrics SHARED process_storage_io.c)
